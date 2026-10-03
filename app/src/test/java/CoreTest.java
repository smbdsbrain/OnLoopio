import org.junit.Test;

/** Run the same portable fixtures in Gradle CI and from javac on API-17 development hosts. */
public final class CoreTest {
    @Test public void api()throws Exception {ApiTest.main(new String[0]);}
    @Test public void feedback()throws Exception {FeedbackTest.main(new String[0]);}
}
