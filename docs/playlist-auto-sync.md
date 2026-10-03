# Playlist synchronization

**Settings → Playlist synchronization** controls automatic checks, intervals, charging and home Wi-Fi triggers. You can check immediately from the same screen. Following a playlist keeps its membership current; selecting **Keep playlist offline** also queues its tracks for download when the configured home Wi-Fi and Navidrome server are available.

If a sync fails, previously saved metadata and audio remain available. The queue resumes after connectivity returns unless you paused downloads or enabled Force offline mode. See [online and offline behavior](sync-model.md).

The same service sends pending likes and listens and refreshes server favorites. Feedback uploads run even when downloads are paused or the download queue is empty. Online changes request an immediate feedback pass without fetching the catalog again. **Check now** checks playlists and sends feedback; the status includes remaining events and any incomplete stage.
