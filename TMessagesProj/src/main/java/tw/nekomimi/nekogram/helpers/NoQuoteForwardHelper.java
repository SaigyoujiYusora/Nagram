package tw.nekomimi.nekogram.helpers;

import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import tw.nekomimi.nekogram.utils.AlertUtil;

/**
 * Fallback for "no quote forward" (forward with drop_author).
 * <p>
 * When messages.forwardMessages with drop_author fails (e.g. CHAT_FORWARDS_RESTRICTED),
 * the content is copied on the client side instead of relying on the server forward flag:
 * text is re-sent with its formatting entities, media is re-sent (protected media is
 * downloaded and uploaded again as a new file), albums are kept as albums.
 */
public class NoQuoteForwardHelper extends BaseController implements NotificationCenter.NotificationCenterDelegate {

    private static final SparseArray<NoQuoteForwardHelper> Instance = new SparseArray<>();
    private static final int MAX_DOWNLOAD_RETRIES = 3;

    private final HashMap<String, PendingDownload> pendingDownloads = new HashMap<>();
    private boolean observing;

    private static class PendingDownload {
        TLObject target;
        MessageObject parent;
        int retries;
        final ArrayList<Utilities.Callback<File>> callbacks = new ArrayList<>();
    }

    private static class CopyTask {
        long peer;
        boolean hideCaption;
        boolean notify;
        int scheduleDate;
        int scheduleRepeatPeriod;
        MessageObject replyToTopMsg;
        long payStars;
        long monoForumPeerId;
        MessageSuggestionParams suggestionParams;
        int skipped;
    }

    public NoQuoteForwardHelper(int num) {
        super(num);
    }

    public static NoQuoteForwardHelper getInstance(int num) {
        NoQuoteForwardHelper localInstance = Instance.get(num);
        if (localInstance == null) {
            synchronized (NoQuoteForwardHelper.class) {
                localInstance = Instance.get(num);
                if (localInstance == null) {
                    Instance.put(num, localInstance = new NoQuoteForwardHelper(num));
                }
            }
        }
        return localInstance;
    }

    /**
     * Whether copying the content could succeed where the server forward failed.
     * Errors caused by missing rights, payments or rate limits would fail for a copy too.
     */
    public static boolean canFallbackToCopy(TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return false;
        }
        String text = error.text;
        return !(text.startsWith("ALLOW_PAYMENT_REQUIRED")
                || text.startsWith("FLOOD_WAIT")
                || text.startsWith("FLOOD_PREMIUM_WAIT")
                || text.startsWith("SLOWMODE_WAIT")
                || text.startsWith("CHAT_SEND_")
                || text.equals("CHAT_WRITE_FORBIDDEN")
                || text.equals("CHAT_ADMIN_REQUIRED")
                || text.equals("CHAT_RESTRICTED")
                || text.equals("USER_BANNED_IN_CHANNEL")
                || text.equals("USER_IS_BLOCKED")
                || text.equals("YOU_BLOCKED_USER")
                || text.equals("PEER_ID_INVALID")
                || text.equals("SCHEDULE_TOO_MUCH")
                || text.equals("SCHEDULE_DATE_TOO_LATE"));
    }

    public static void showFallbackToast(TLRPC.TL_error error) {
        AlertUtil.showToast(LocaleController.formatString(R.string.NoQuoteForwardCopyFallback, error != null && error.text != null ? error.text : ""));
    }

    /**
     * Copy the given messages to {@code peer} as new messages. Must be called on the UI thread.
     */
    public void copyMessages(ArrayList<MessageObject> messages, long peer, boolean hideCaption, boolean notify, int scheduleDate, int scheduleRepeatPeriod, MessageObject replyToTopMsg, long payStars, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        if (messages == null || messages.isEmpty() || peer == 0) {
            return;
        }
        CopyTask task = new CopyTask();
        task.peer = peer;
        task.hideCaption = hideCaption;
        task.notify = notify;
        task.scheduleDate = scheduleDate;
        task.scheduleRepeatPeriod = scheduleRepeatPeriod;
        task.replyToTopMsg = replyToTopMsg;
        task.payStars = payStars;
        task.monoForumPeerId = monoForumPeerId;
        task.suggestionParams = suggestionParams;

        // split into units: single messages, or consecutive messages of the same album
        ArrayList<ArrayList<MessageObject>> units = new ArrayList<>();
        ArrayList<MessageObject> current = null;
        long currentGroupId = 0;
        for (int a = 0; a < messages.size(); a++) {
            MessageObject messageObject = messages.get(a);
            if (messageObject == null || messageObject.messageOwner == null) {
                continue;
            }
            long groupId = messageObject.messageOwner.grouped_id;
            if (groupId != 0 && current != null && groupId == currentGroupId) {
                current.add(messageObject);
                continue;
            }
            current = new ArrayList<>();
            current.add(messageObject);
            units.add(current);
            currentGroupId = groupId;
        }
        processUnit(task, units, 0);
    }

    private void processUnit(CopyTask task, ArrayList<ArrayList<MessageObject>> units, int index) {
        if (index >= units.size()) {
            if (task.skipped > 0) {
                AlertUtil.showToast(LocaleController.formatString(R.string.NoQuoteForwardCopySkipped, task.skipped));
            }
            return;
        }
        final ArrayList<MessageObject> unit = units.get(index);
        final File[] files = new File[unit.size()];
        final int[] waiting = {1};
        final Runnable onFilesReady = () -> Utilities.globalQueue.postRunnable(() -> {
            // heavy work (bitmap decoding / thumbnails) off the UI thread
            final TLObject[] localMedia = new TLObject[unit.size()];
            for (int a = 0; a < unit.size(); a++) {
                if (files[a] != null) {
                    try {
                        localMedia[a] = prepareLocalMedia(unit.get(a), files[a]);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                sendUnit(task, unit, localMedia, files);
                processUnit(task, units, index + 1);
            });
        });
        for (int a = 0; a < unit.size(); a++) {
            MessageObject messageObject = unit.get(a);
            if (!needsLocalCopy(messageObject)) {
                continue;
            }
            File file = findLocalFile(messageObject);
            if (file != null) {
                files[a] = file;
                continue;
            }
            waiting[0]++;
            final int fileIndex = a;
            download(messageObject, loadedFile -> {
                files[fileIndex] = loadedFile;
                if (--waiting[0] == 0) {
                    onFilesReady.run();
                }
            });
        }
        if (--waiting[0] == 0) {
            onFilesReady.run();
        }
    }

    private void sendUnit(CopyTask task, ArrayList<MessageObject> unit, TLObject[] localMedia, File[] files) {
        ArrayList<SendMessagesHelper.SendMessageParams> paramsList = new ArrayList<>();
        for (int a = 0; a < unit.size(); a++) {
            SendMessagesHelper.SendMessageParams params = createParams(task, unit.get(a), localMedia[a], files[a]);
            if (params == null) {
                task.skipped++;
            } else {
                paramsList.add(params);
            }
        }
        int mediaCount = 0;
        for (int a = 0; a < paramsList.size(); a++) {
            SendMessagesHelper.SendMessageParams params = paramsList.get(a);
            if (params.photo != null || params.document != null) {
                mediaCount++;
            }
        }
        if (unit.size() > 1 && mediaCount > 1) {
            // keep albums as albums
            String groupId = String.valueOf(Utilities.random.nextLong());
            SendMessagesHelper.SendMessageParams last = null;
            for (int a = 0; a < paramsList.size(); a++) {
                SendMessagesHelper.SendMessageParams params = paramsList.get(a);
                if (params.photo == null && params.document == null) {
                    continue;
                }
                if (params.params == null) {
                    params.params = new HashMap<>();
                }
                params.params.put("groupId", groupId);
                last = params;
            }
            if (last != null) {
                last.params.put("final", "1");
            }
        }
        for (int a = 0; a < paramsList.size(); a++) {
            getSendMessagesHelper().sendMessage(paramsList.get(a));
        }
    }

    private SendMessagesHelper.SendMessageParams createParams(CopyTask task, MessageObject messageObject, TLObject localMedia, File localFile) {
        TLRPC.Message owner = messageObject.messageOwner;
        TLRPC.MessageMedia media = owner.media;
        boolean hasMedia = media != null && !(media instanceof TLRPC.TL_messageMediaEmpty) && !(media instanceof TLRPC.TL_messageMediaWebPage);
        String caption = hasMedia && task.hideCaption ? null : owner.message;
        ArrayList<TLRPC.MessageEntity> entities = TextUtils.isEmpty(caption) ? null : copyEntities(owner.entities);
        MessageObject reply = task.replyToTopMsg;
        // protected media must be uploaded as a new local file; skip if download failed
        if (needsLocalCopy(messageObject) && localMedia == null) {
            return null;
        }

        SendMessagesHelper.SendMessageParams params;
        if (!hasMedia) {
            if (TextUtils.isEmpty(owner.message)) {
                return null;
            }
            TLRPC.WebPage webPage = media instanceof TLRPC.TL_messageMediaWebPage ? media.webpage : null;
            params = SendMessagesHelper.SendMessageParams.of(owner.message, task.peer, reply, reply, webPage, true, entities, null, null, task.notify, task.scheduleDate, task.scheduleRepeatPeriod, null, false);
        } else if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo instanceof TLRPC.TL_photo) {
            boolean local = localMedia instanceof TLRPC.TL_photo;
            TLRPC.TL_photo photo = local ? (TLRPC.TL_photo) localMedia : (TLRPC.TL_photo) media.photo;
            params = SendMessagesHelper.SendMessageParams.of(photo, null, task.peer, reply, reply, caption, entities, null, null, task.notify, task.scheduleDate, task.scheduleRepeatPeriod, 0, local ? null : messageObject, false, media.spoiler);
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document instanceof TLRPC.TL_document) {
            boolean local = localMedia instanceof TLRPC.TL_document && localFile != null;
            TLRPC.TL_document document = local ? (TLRPC.TL_document) localMedia : (TLRPC.TL_document) media.document;
            String path = local ? localFile.getAbsolutePath() : null;
            params = SendMessagesHelper.SendMessageParams.of(document, null, path, task.peer, reply, reply, caption, entities, null, null, task.notify, task.scheduleDate, task.scheduleRepeatPeriod, 0, local ? null : messageObject, null, false, media.spoiler);
        } else if (media instanceof TLRPC.TL_messageMediaVenue || media instanceof TLRPC.TL_messageMediaGeo) {
            params = SendMessagesHelper.SendMessageParams.of(media, task.peer, reply, reply, null, null, task.notify, task.scheduleDate, task.scheduleRepeatPeriod);
        } else if (media.phone_number != null) {
            TLRPC.User user = new TLRPC.TL_userContact_old2();
            user.phone = media.phone_number;
            user.first_name = media.first_name;
            user.last_name = media.last_name;
            user.id = media.user_id;
            params = SendMessagesHelper.SendMessageParams.of(user, task.peer, reply, reply, null, null, task.notify, task.scheduleDate, task.scheduleRepeatPeriod);
        } else {
            // polls, dice, games, invoices, stories, giveaways, paid media, live locations...
            return null;
        }
        params.invert_media = owner.invert_media;
        params.canUsePangu = false;
        params.payStars = task.payStars;
        params.monoForumPeer = task.monoForumPeerId;
        params.suggestionParams = task.suggestionParams;
        return params;
    }

    private ArrayList<TLRPC.MessageEntity> copyEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>(entities.size());
        for (int a = 0; a < entities.size(); a++) {
            TLRPC.MessageEntity entity = entities.get(a);
            if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                TLRPC.TL_inputMessageEntityMentionName mention = new TLRPC.TL_inputMessageEntityMentionName();
                mention.offset = entity.offset;
                mention.length = entity.length;
                mention.user_id = getMessagesController().getInputUser(((TLRPC.TL_messageEntityMentionName) entity).user_id);
                result.add(mention);
            } else {
                result.add(entity);
            }
        }
        return result;
    }

    private boolean isProtected(MessageObject messageObject) {
        return messageObject.messageOwner.noforwards || getMessagesController().isPeerNoForwards(messageObject.getDialogId());
    }

    /**
     * Protected media can't be re-sent by reference, so it has to be uploaded again from a local file.
     * Stickers are public documents and are always re-sent by reference.
     */
    private boolean needsLocalCopy(MessageObject messageObject) {
        TLRPC.MessageMedia media = messageObject.messageOwner.media;
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return media.photo instanceof TLRPC.TL_photo && isProtected(messageObject);
        } else if (media instanceof TLRPC.TL_messageMediaDocument) {
            return media.document instanceof TLRPC.TL_document && !messageObject.isAnyKindOfSticker() && isProtected(messageObject);
        }
        return false;
    }

    private TLObject getDownloadTarget(MessageObject messageObject) {
        TLRPC.MessageMedia media = messageObject.messageOwner.media;
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            return FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
        } else if (media instanceof TLRPC.TL_messageMediaDocument) {
            return media.document;
        }
        return null;
    }

    private static boolean isUsableFile(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    private File findLocalFile(MessageObject messageObject) {
        String attachPath = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(attachPath)) {
            File file = new File(attachPath);
            if (isUsableFile(file)) {
                return file;
            }
        }
        TLObject target = getDownloadTarget(messageObject);
        if (target != null) {
            File file = getFileLoader().getPathToAttach(target, false);
            if (isUsableFile(file)) {
                return file;
            }
            file = getFileLoader().getPathToAttach(target, true);
            if (isUsableFile(file)) {
                return file;
            }
        }
        File file = getFileLoader().getPathToMessage(messageObject.messageOwner);
        if (isUsableFile(file)) {
            return file;
        }
        return null;
    }

    private void download(MessageObject messageObject, Utilities.Callback<File> callback) {
        TLObject target = getDownloadTarget(messageObject);
        String key = target != null ? FileLoader.getAttachFileName(target) : null;
        if (TextUtils.isEmpty(key)) {
            callback.run(null);
            return;
        }
        PendingDownload pending = pendingDownloads.get(key);
        if (pending != null) {
            pending.callbacks.add(callback);
            return;
        }
        pending = new PendingDownload();
        pending.target = target;
        pending.parent = messageObject;
        pending.callbacks.add(callback);
        pendingDownloads.put(key, pending);
        if (!observing) {
            observing = true;
            getNotificationCenter().addObserver(this, NotificationCenter.fileLoaded);
            getNotificationCenter().addObserver(this, NotificationCenter.fileLoadFailed);
        }
        if (!startLoad(pending)) {
            finishDownload(key, null);
        }
    }

    private boolean startLoad(PendingDownload pending) {
        if (pending.target instanceof TLRPC.Document) {
            getFileLoader().loadFile((TLRPC.Document) pending.target, pending.parent, FileLoader.PRIORITY_HIGH, 0);
            return true;
        } else if (pending.target instanceof TLRPC.PhotoSize) {
            ImageLocation location = ImageLocation.getForPhoto((TLRPC.PhotoSize) pending.target, pending.parent.messageOwner.media.photo);
            if (location == null) {
                return false;
            }
            getFileLoader().loadFile(location, pending.parent, "jpg", FileLoader.PRIORITY_HIGH, 0);
            return true;
        }
        return false;
    }

    private void finishDownload(String key, File file) {
        PendingDownload pending = pendingDownloads.remove(key);
        if (pendingDownloads.isEmpty() && observing) {
            observing = false;
            getNotificationCenter().removeObserver(this, NotificationCenter.fileLoaded);
            getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadFailed);
        }
        if (pending == null) {
            return;
        }
        File result = isUsableFile(file) ? file : null;
        for (int a = 0; a < pending.callbacks.size(); a++) {
            pending.callbacks.get(a).run(result);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || args == null || args.length == 0 || !(args[0] instanceof String)) {
            return;
        }
        String key = (String) args[0];
        if (id == NotificationCenter.fileLoaded) {
            if (pendingDownloads.containsKey(key)) {
                finishDownload(key, args.length > 1 && args[1] instanceof File ? (File) args[1] : null);
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            PendingDownload pending = pendingDownloads.get(key);
            if (pending == null) {
                return;
            }
            boolean canceled = args.length > 1 && args[1] instanceof Integer && (Integer) args[1] == 1;
            // the load may be shared with (and cancelled by) an image receiver, retry a few times
            if (canceled && pending.retries < MAX_DOWNLOAD_RETRIES) {
                pending.retries++;
                AndroidUtilities.runOnUIThread(() -> {
                    if (pendingDownloads.get(key) == pending && !startLoad(pending)) {
                        finishDownload(key, null);
                    }
                });
            } else {
                finishDownload(key, null);
            }
        }
    }

    /**
     * Build a new local media object from a downloaded file so it gets uploaded as a new file.
     * Runs on a background thread.
     */
    private TLObject prepareLocalMedia(MessageObject messageObject, File file) {
        TLRPC.MessageMedia media = messageObject.messageOwner.media;
        String path = file.getAbsolutePath();
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return getSendMessagesHelper().generatePhotoSizes(null, path, null, true);
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            TLRPC.Document source = media.document;
            TLRPC.TL_document document = new TLRPC.TL_document();
            document.id = 0;
            document.access_hash = 0;
            document.file_reference = new byte[0];
            document.date = getConnectionsManager().getCurrentTime();
            document.mime_type = TextUtils.isEmpty(source.mime_type) ? "application/octet-stream" : source.mime_type;
            document.size = file.length();
            document.dc_id = 0;
            // keeps the type: video / round / gif / voice / audio / file name ...
            document.attributes = new ArrayList<>(source.attributes);
            if (MessageObject.isVideoDocument(source) || MessageObject.isRoundVideoDocument(source) || MessageObject.isNewGifDocument(source)) {
                Bitmap bitmap = SendMessagesHelper.createVideoThumbnailAtTime(path, 0);
                if (bitmap == null) {
                    bitmap = SendMessagesHelper.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND);
                }
                if (bitmap != null) {
                    TLRPC.PhotoSize thumb = ImageLoader.scaleAndSaveImage(bitmap, 320, 320, 80, false);
                    if (thumb != null) {
                        document.thumbs.add(thumb);
                        document.flags |= 1;
                    }
                }
            }
            return document;
        }
        return null;
    }
}
