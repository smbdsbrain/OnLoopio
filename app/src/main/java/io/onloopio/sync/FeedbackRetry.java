package io.onloopio.sync;
import java.io.IOException;
import io.onloopio.api.ApiException;
/** Typed failures: unknown IO text never becomes a permanent rejection. */
public final class FeedbackRetry {
    public static String kind(IOException error){if(error instanceof javax.net.ssl.SSLException)return "account";if(error instanceof ApiException){int code=((ApiException)error).code;return code==40 || code==41 || code==50?"account":code==70?"quarantined":"transient";}return "transient";}
    public static long delay(int attempts){long[] intervals={60000,300000,900000,3600000};return intervals[Math.max(0,Math.min(attempts-1,3))];}
}
