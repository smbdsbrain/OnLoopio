package io.onloopio.player;
import java.util.HashSet;
import java.util.Set;
/** Repeat cannot turn an error recovery into an infinite loop. Cancellation never enters here. */
public final class RecoveryPass {
    private final Set<String> failed=new HashSet<String>();
    public boolean fail(String entry){return failed.add(entry);}
    public boolean tried(String entry){return failed.contains(entry);}
    public void reset(){failed.clear();}
}
