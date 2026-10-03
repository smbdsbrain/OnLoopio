package io.onloopio.model;

/** The latest desired state; acknowledgements must match its revision. */
public final class LikeChange {
    public final String account, songId;
    public final boolean liked;
    public final long revision;
    public LikeChange(String account,String songId,boolean liked,long revision) {
        this.account=account;this.songId=songId;this.liked=liked;this.revision=revision;
    }
}
