package io.onloopio.player;

import io.onloopio.model.Song;
import java.util.*;

/** Occurrences, traversal and source order are separate. No Android or clock dependency. */
public final class PlaybackQueue {
    public static final int LIMIT=10000;
    public static final class Entry {
        public final String id,account; public final Song song;
        public Entry(String id,String account,Song song){this.id=id;this.account=account==null?"":account;this.song=song;}
    }
    public final String sessionId;
    private final List<Entry> entries=new ArrayList<Entry>();
    private final Map<String,Entry> byId=new HashMap<String,Entry>();
    private final List<String> history=new ArrayList<String>(),pending=new ArrayList<String>();
    private final Random random; private int cursor=-1,forcedCount; private boolean shuffle; private int repeat;
    public PlaybackQueue(Random random){this(UUID.randomUUID().toString(),random);}
    public PlaybackQueue(String id,Random random){sessionId=id;this.random=random;}
    public List<Entry> entries(){return Collections.unmodifiableList(entries);}
    public List<String> history(){return Collections.unmodifiableList(history);}
    public List<String> pending(){return Collections.unmodifiableList(pending);}
    public int cursor(){return cursor;} public boolean shuffle(){return shuffle;} public int repeat(){return repeat;}
    public int forcedCount(){return forcedCount;}
    public void restoreForced(int count){if(count<0 || count>pending.size())throw new IllegalArgumentException("Invalid priority count");forcedCount=count;}
    public Entry entry(String id){return byId.get(id);}
    public Entry current(){return cursor>=0 && cursor<history.size()?entry(history.get(cursor)):null;}
    public int position(){return entries.indexOf(current());}
    public void replace(List<Entry> list,int selected){
        if(list.size()>LIMIT)throw new IllegalArgumentException("Queue too large");
        entries.clear();entries.addAll(list);byId.clear();for(Entry e:list)if(byId.put(e.id,e)!=null)throw new IllegalArgumentException("Duplicate entry");history.clear();pending.clear();cursor=-1;forcedCount=0;
        if(entries.isEmpty())return;
        selected=Math.max(0,Math.min(selected,entries.size()-1));
        history.add(entries.get(selected).id);cursor=0;
        for(int n=selected+1;n<entries.size();n++)pending.add(entries.get(n).id);
        if(shuffle){for(int n=0;n<selected;n++)pending.add(entries.get(n).id);Collections.shuffle(pending,random);}
    }
    public void restore(List<Entry> list,List<String> visited,List<String> remaining,int at,boolean mixed,int mode){
        if(list.size()>LIMIT || visited.size()>LIMIT || remaining.size()>LIMIT)throw new IllegalArgumentException("Queue too large");
        entries.clear();entries.addAll(list);byId.clear();for(Entry e:list)byId.put(e.id,e);Set<String> ids=new HashSet<String>();for(Entry e:list)if(!ids.add(e.id))throw new IllegalArgumentException("Duplicate entry");
        for(String id:visited)if(!ids.contains(id))throw new IllegalArgumentException("Missing history entry");
        Set<String> unique=new HashSet<String>();for(String id:remaining)if(!ids.contains(id) || !unique.add(id))throw new IllegalArgumentException("Invalid pending entry");
        if(at < -1 || at>=visited.size())throw new IllegalArgumentException("Invalid cursor");
        history.clear();history.addAll(visited);pending.clear();pending.addAll(remaining);cursor=at;shuffle=mixed;repeat=Math.max(0,Math.min(2,mode));forcedCount=0;
    }
    public void configure(boolean mixed,int mode){
        repeat=Math.max(0,Math.min(2,mode));if(mixed==shuffle)return;shuffle=mixed;
        List<String> remaining=pending.subList(forcedCount,pending.size());if(mixed)Collections.shuffle(remaining,random);
        else {final Map<String,Integer> order=new HashMap<String,Integer>();for(int n=0;n<entries.size();n++)order.put(entries.get(n).id,n);Collections.sort(remaining,new Comparator<String>(){public int compare(String a,String b){return order.get(a)-order.get(b);}});}
    }
    public Entry peek(boolean natural){
        if(current()==null)return null;
        if(natural && repeat==1)return current();
        if(cursor+1<history.size())return entry(history.get(cursor+1));
        if(!pending.isEmpty())return entry(pending.get(0));
        if(repeat==2 && !entries.isEmpty()){round();return entry(pending.get(0));}
        return null;
    }
    public Entry next(boolean natural){
        if(current()==null)return null;
        if(natural && repeat==1)return current();
        if(cursor+1<history.size())return entry(history.get(++cursor));
        if(pending.isEmpty()){
            if(repeat!=2)return null;
            round();
        }
        String id=pending.remove(0);if(forcedCount>0)forcedCount--;history.add(id);if(history.size()>LIMIT)history.remove(0);cursor=history.size()-1;return entry(id);
    }
    private void round(){String last=current()==null?"":current().id;for(Entry e:entries)pending.add(e.id);if(shuffle){Collections.shuffle(pending,random);if(pending.size()>1 && pending.get(0).equals(last))Collections.swap(pending,0,1);}}
    public Entry previous(){return cursor>0?entry(history.get(--cursor)):null;}
    public void jump(String id){if(entry(id)==null)return;while(history.size()>cursor+1)history.remove(history.size()-1);int at=pending.indexOf(id);if(at>=0 && at<forcedCount)forcedCount--;pending.remove(id);history.add(id);cursor=history.size()-1;if(history.size()>LIMIT){history.remove(0);cursor--;}}
    public void append(List<Entry> list,boolean next){
        if(entries.size()+list.size()>LIMIT)throw new IllegalArgumentException("Queue too large");
        Set<String> unique=new HashSet<String>();for(Entry e:entries)unique.add(e.id);for(Entry e:list)if(!unique.add(e.id))throw new IllegalArgumentException("Duplicate entry");
        entries.addAll(list);for(Entry e:list)byId.put(e.id,e);List<String> ids=new ArrayList<String>();for(Entry e:list)ids.add(e.id);
        if(current()==null && !list.isEmpty()){history.clear();history.add(ids.remove(0));cursor=0;}
        if(next){returnForward();pending.addAll(0,ids);forcedCount+=ids.size();}else pending.addAll(ids);
    }
    private void returnForward(){
        List<String> forward=new ArrayList<String>(history.subList(cursor+1,history.size()));
        while(history.size()>cursor+1)history.remove(history.size()-1);
        for(int n=forward.size()-1;n>=0;n--){String id=forward.get(n);int at=pending.indexOf(id);if(at>=0 && at<forcedCount)forcedCount--;pending.remove(id);pending.add(forcedCount,id);}
    }
    public void moveNext(String id){if(entry(id)==null || current()!=null && current().id.equals(id))return;returnForward();int at=pending.indexOf(id);if(at>=0 && at<forcedCount)forcedCount--;pending.remove(id);pending.add(0,id);forcedCount++;}
    public Entry remove(String id){
        Entry old=current(),successor=null;
        if(old!=null && old.id.equals(id))successor=next(false);
        entries.remove(entry(id));byId.remove(id);int at=pending.indexOf(id);if(at>=0 && at<forcedCount)forcedCount--;pending.remove(id);
        for(int n=history.size()-1;n>=0;n--)if(history.get(n).equals(id)){history.remove(n);if(n<=cursor)cursor--;}
        if(cursor>=history.size())cursor=history.size()-1;
        if(old!=null && old.id.equals(id) && successor==null){cursor=-1;return null;}
        return current();
    }
    public void clearRemaining(){pending.clear();forcedCount=0;while(history.size()>cursor+1)history.remove(history.size()-1);Set<String> keep=new HashSet<String>(history);for(Iterator<Entry> it=entries.iterator();it.hasNext();)if(!keep.contains(it.next().id))it.remove();byId.keySet().retainAll(keep);}
}
