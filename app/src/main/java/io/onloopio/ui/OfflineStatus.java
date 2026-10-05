package io.onloopio.ui;
import android.content.Context;
import io.onloopio.db.GenerationStore;
public final class OfflineStatus {
    public static String label(Context c,GenerationStore.Summary s){String text=Ui.label(c,s.active?s.degraded?"Offline degraded":"Ready offline":"Preparing offline")+": "+(s.active?s.ready+"/"+s.required:s.stagingReady+"/"+s.stagingRequired);if(s.active){text+=" · "+s.unique+" "+Ui.label(c,"unique files");if(s.knownDuration)text+=" · "+s.seconds/3600+" h "+s.seconds%3600/60+" min";}if(s.stagingRequired>0 && s.active)text+="\n"+Ui.label(c,"New version")+": "+s.stagingReady+"/"+s.stagingRequired+" · "+Ui.label(c,"Previous version remains available");if(s.detached)text+=" · "+Ui.label(c,"Detached playlist");return text;}
}
