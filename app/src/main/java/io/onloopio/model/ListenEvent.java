package io.onloopio.model;

/** One qualified playback, with the original start time rather than the upload time. */
public final class ListenEvent {
    public final String sessionId, account, songId;
    public final long time;
    public ListenEvent(String sessionId,String account,String songId,long time) {
        this.sessionId=sessionId;this.account=account;this.songId=songId;this.time=time;
    }
}
