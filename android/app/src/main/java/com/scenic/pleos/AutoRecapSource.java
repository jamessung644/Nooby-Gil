package com.scenic.pleos;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Read-only discovery within a folder explicitly connected through the system picker. */
public final class AutoRecapSource {
    private static final int MAX_DEPTH=3,MAX_FOLDERS=64,MAX_DOCUMENTS=2000,MAX_VIDEOS=64;
    private static final long SETTLE_MS=60_000;
    private static final String[] COLUMNS={
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE
    };

    public static final class SourceVideo {
        public final Uri uri;
        public final String title;
        /** Provider modification time only; this is not assumed to be the filming date. */
        public final long modifiedAt;
        public SourceVideo(Uri uri,String title,long modifiedAt){this.uri=uri;this.title=title;this.modifiedAt=modifiedAt;}
    }

    private static final class Folder {
        final String id;
        final int depth;
        Folder(String id,int depth){this.id=id;this.depth=depth;}
    }

    private AutoRecapSource(){}

    /** Call on a worker thread. Requires the caller's persisted read grant for the selected tree. */
    public static List<SourceVideo> scan(Context context,Uri tree,RecapVideoExporter.CancelCheck cancel) throws Exception {
        return scan(context,tree,Collections.emptySet(),cancel);
    }

    /** Exclude known, pending, or deferred files before applying the per-scan video limit. */
    public static List<SourceVideo> scan(Context context,Uri tree,Set<String> excludedUris,RecapVideoExporter.CancelCheck cancel) throws Exception {
        if(context==null||tree==null||!"content".equals(tree.getScheme())||!DocumentsContract.isTreeUri(tree))
            throw new IllegalArgumentException("A connected document tree is required");
        check(cancel);
        Set<String> excluded=excludedUris==null?Collections.emptySet():new HashSet<>(excludedUris);
        String root=DocumentsContract.getTreeDocumentId(tree);
        if(root==null||root.isEmpty())throw new IllegalArgumentException("The connected folder is invalid");
        long settledBefore=System.currentTimeMillis()-SETTLE_MS;
        ArrayDeque<Folder> pending=new ArrayDeque<>();
        Set<String> folders=new HashSet<>(),seenVideos=new HashSet<>();
        ArrayList<SourceVideo> videos=new ArrayList<>();
        folders.add(root);pending.add(new Folder(root,0));
        int documents=0;
        while(!pending.isEmpty()&&documents<MAX_DOCUMENTS){
            check(cancel);
            Folder folder=pending.removeFirst();
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,folder.id);
            try(Cursor cursor=context.getContentResolver().query(children,COLUMNS,null,null,null)){
                if(cursor==null)throw new IOException("The connected folder could not be read");
                int idColumn=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int modifiedColumn=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                int sizeColumn=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
                while(documents<MAX_DOCUMENTS&&cursor.moveToNext()){
                    check(cancel);documents++;
                    String id=cursor.getString(idColumn),mime=cursor.getString(mimeColumn);
                    if(id==null||id.isEmpty()||id.length()>4096)continue;
                    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){
                        if(folder.depth<MAX_DEPTH&&folders.size()<MAX_FOLDERS&&folders.add(id))pending.addLast(new Folder(id,folder.depth+1));
                        continue;
                    }
                    String name=cursor.getString(nameColumn);
                    if(!isVideo(mime,name))continue;
                    long modified=cursor.isNull(modifiedColumn)?0:Math.max(0,cursor.getLong(modifiedColumn));
                    long bytes=cursor.isNull(sizeColumn)?-1:cursor.getLong(sizeColumn);
                    // A just-written or zero-byte file may still be recording. Pick it up on a later scan.
                    if(modified>settledBefore||bytes==0)continue;
                    Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                    String uriString=uri.toString();
                    if(!excluded.contains(uriString)&&seenVideos.add(uriString))videos.add(new SourceVideo(uri,title(name),modified));
                }
            }
        }
        check(cancel);
        Collections.sort(videos,Comparator.comparingLong((SourceVideo video)->video.modifiedAt).reversed()
            .thenComparing(video->video.uri.toString()));
        return Collections.unmodifiableList(new ArrayList<>(videos.subList(0,Math.min(MAX_VIDEOS,videos.size()))));
    }

    private static boolean isVideo(String mime,String name){
        if(mime!=null&&mime.toLowerCase(Locale.US).startsWith("video/"))return true;
        String filename=name==null?"":name.toLowerCase(Locale.US);
        return filename.endsWith(".mp4")||filename.endsWith(".mov")||filename.endsWith(".mkv")||filename.endsWith(".webm");
    }
    private static String title(String value){
        String clean=value==null?"":value.replaceAll("[\\p{Cntrl}]"," ").trim();
        if(clean.isEmpty())return "빌트인캠 영상";
        return clean.length()>120?clean.substring(0,120):clean;
    }
    private static void check(RecapVideoExporter.CancelCheck cancel){
        if(Thread.currentThread().isInterrupted()||(cancel!=null&&cancel.isCancelled()))throw new CancellationException("Folder scan cancelled");
    }
}
