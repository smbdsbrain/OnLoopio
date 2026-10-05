package io.onloopio.ui;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import io.onloopio.device.DeviceSettings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    static int BG = Color.rgb(18, 27, 23), FG = Color.rgb(237, 243, 231), ACCENT = Color.rgb(183, 239, 96);
    static int SELECTION=blend(BG,ACCENT,.20f),SURFACE=blend(BG,FG,.08f),TRACK=blend(BG,FG,.24f);
    static void palette(Context c) {
        DeviceSettings prefs=new DeviceSettings(c);int theme=prefs.number("theme",0);
        BG=theme==1?Color.rgb(238,241,233):theme==2?Color.rgb(10,20,40):Color.rgb(18,27,23);
        FG=theme==1?Color.rgb(23,32,27):Color.rgb(237,243,231);
        ACCENT=AccentPalette.color(theme,AccentPalette.selected(prefs));
        SELECTION=blend(BG,ACCENT,.20f);SURFACE=blend(BG,FG,.08f);TRACK=blend(BG,FG,.24f);
    }
    private static int blend(int base,int tint,float fraction) {
        return Color.rgb(Math.round(Color.red(base)+(Color.red(tint)-Color.red(base))*fraction),
                Math.round(Color.green(base)+(Color.green(tint)-Color.green(base))*fraction),
                Math.round(Color.blue(base)+(Color.blue(tint)-Color.blue(base))*fraction));
    }
    static String deviceInfo(Context c) {
        DeviceSettings prefs=new DeviceSettings(c);
        String time=android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date());
        Intent battery=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(!prefs.flag("battery_percentage",true) || battery==null) return time;
        int scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);
        return time+" · "+battery.getIntExtra(BatteryManager.EXTRA_LEVEL,0)*100/Math.max(1,scale)+"%";
    }
    static int dp(Context c, int n) { return (int)(n * c.getResources().getDisplayMetrics().density + 0.5f); }
    static LinearLayout page(Context c) {
        palette(c);
        LinearLayout layout = new LinearLayout(c); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(BG);
        String wallpaper=new DeviceSettings(c).text("wallpaper","");
        if(!wallpaper.isEmpty()) {
            android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options(); options.inJustDecodeBounds=true;
            android.graphics.BitmapFactory.decodeFile(wallpaper,options); options.inSampleSize=1;
            while(options.outWidth/options.inSampleSize>960 || options.outHeight/options.inSampleSize>720) options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            try { android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(wallpaper,options); if(bitmap!=null) layout.setBackgroundDrawable(new android.graphics.drawable.BitmapDrawable(c.getResources(),bitmap)); }
            catch(RuntimeException ignored) { } catch(OutOfMemoryError ignored) { }
        }
        int pad = dp(c, 10); layout.setPadding(pad,pad,pad,pad); return layout;
    }
    static TextView text(Context c, String text, int size, int color) {
        TextView view = new TextView(c) {
            public void setText(CharSequence value,BufferType type) { super.setText(translate(getContext(),value),type); }
        }; view.setText(text); view.setTextSize(size); view.setTextColor(color); return view;
    }
    static TextView title(Context c, String text) {
        TextView view = text(c,text,20,ACCENT); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setPadding(0,0,0,dp(c,6)); return view;
    }
    static TextView row(Context c, String text) {
        TextView view = text(c,text,16,FG); int p=dp(c,8); view.setPadding(p,p,p,p); view.setMinHeight(dp(c,40));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},new ColorDrawable(SELECTION));
        bg.addState(new int[]{android.R.attr.state_selected},new ColorDrawable(SELECTION));
        bg.addState(new int[]{android.R.attr.state_focused},new ColorDrawable(SELECTION));
        bg.addState(new int[]{},new ColorDrawable(BG)); view.setBackgroundDrawable(bg); return view;
    }
    static TextView action(Context c, String label, View.OnClickListener listener) {
        TextView view = row(c,label); view.setFocusable(true); view.setOnClickListener(listener); return view;
    }
    private static final java.util.Map<String,String> RU=new java.util.HashMap<String,String>();
    public static String label(Context context,String value){return translate(context,value).toString();}
    static {
        String[][] milestoneLabels={{"Loading playback queue","Загрузка очереди воспроизведения"},{"Playback checkpoint failed","Не удалось сохранить воспроизведение"},{"Saved audio","Сохранённое аудио"},{"Cached metadata","Метаданные сохранены"},{"Idle shutdown after 30 minutes","Выключаться после 30 минут простоя"},{"Saved playback unavailable","Сохранённое воспроизведение недоступно"},{"Release offline snapshot","Освободить офлайн-набор"},{"Release offline snapshot?","Освободить офлайн-набор?"},{"Feedback queue","Очередь отправки действий"},{"Retry feedback","Повторить отправку"},{"Discard feedback","Удалить событие отправки"},{"Discard feedback?","Удалить событие отправки?"},{"quarantine","Требует проверки"},{"quarantined","Требует проверки"},{"transient","Ожидание повтора"},{"account","Проверьте аккаунт"},{"pending","Ожидает отправки"},{"Automatic feedback sync","Автоматическая отправка действий"},{"Send feedback now","Отправить действия сейчас"},{"Energy profile","Энергорежим"},{"Update at home","Обновляться дома"},{"Large downloads while charging","Большие загрузки на зарядке"},{"Listening history","История прослушиваний"},{"Next page","Следующая страница"},{"Previous page","Предыдущая страница"},{"Stop on playback error","Остановиться при ошибке"},{"Offline audio profile","Профиль офлайн-аудио"},{"Compatible","Совместимый"},{"Original","Оригинал"},{"Compact","Компактный"},{"ReplayGain","ReplayGain"},{"Track (attenuation)","Трек (ослабление)"},{"Album (attenuation)","Альбом (ослабление)"},{"Experimental local gapless","Экспериментальный gapless локальных файлов"},{"Ready offline","Готов офлайн"},{"Preparing offline","Подготовка офлайн"},{"Offline degraded","Офлайн-набор неполный"},{"New version","Новая версия"},{"Previous version remains available","Предыдущая версия доступна"},{"Detached playlist","Плейлист удалён с сервера"},{"unique files","уникальных файлов"},{"Retry update","Повторить обновление"},{"Cancel staging update","Отменить подготовку обновления"},{"Waiting for retry","Ожидание повтора"},{"Waiting for battery","Ожидание заряда"},{"Waiting for charger","Ожидание зарядки"},{"Critical battery","Критический заряд"},{"Automatic sync disabled","Автоматическая синхронизация выключена"},{"Check account settings","Проверьте настройки аккаунта"}};for(String[] pair:milestoneLabels)RU.put(pair[0],pair[1]);
        String[][] reliability={{"Event time uncertain","Время события неточно"},{"Requested audio profile","Запрошенный профиль аудио"},{"listen","Прослушивание"},{"like","Лайк"},{"playing","Воспроизводится"},{"completed","Завершено"},{"skipped","Пропущено"},{"error","Ошибка"},{"USB storage requires system installation","USB-накопитель требует системной установки"},{"Playback queue","Очередь воспроизведения"},{"Remove from queue","Убрать из очереди"},{"Clear remaining","Очистить оставшиеся"},{"Storage unavailable","Накопитель недоступен"},{"Queue limit reached","Достигнут предел очереди"},{"No playable tracks","Нет доступных треков"},{"Cannot decode audio","Не удалось декодировать аудио"},{"Local file unavailable","Локальный файл недоступен"}};for(String[] pair:reliability)RU.put(pair[0],pair[1]);
        String[][] feedback={{"Like","Лайк"},{"Remove like","Снять лайк"},{"Liked","Лайк поставлен"},{"Like removed","Лайк снят"},{"Favorite tracks","Любимые треки"},{"Double Play: like","Двойной Play: лайк"},{"Synchronization","Синхронизация"},{"Synchronizing Navidrome…","Синхронизация Navidrome…"},{"Sending likes and listens…","Отправка лайков и прослушиваний…"},{"Pending feedback","Ожидают отправки"}};
        for(String[] pair:feedback)RU.put(pair[0],pair[1]);
        String[][] local={{"Scan Music folder","Сканировать папку Music"},{"Share Music over USB","Открыть Music по USB"},{"Return USB storage to player","Вернуть накопитель плееру"},{"Share storage with computer? Playback pauses until storage returns.","Открыть накопитель на ПК? Музыка остановится до возврата накопителя."},{"USB storage shared with computer","Накопитель подключён к ПК"},{"Music library updated","Библиотека обновлена"},{"Ready","Готово"},{"Remove downloaded audio","Удалить загрузки"}};
        for(String[] pair:local)RU.put(pair[0],pair[1]);
        String[][] accents={{"Accent color","Акцентный цвет"},{"Theme default","Цвет темы"},{"Lime","Лайм"},{"Emerald","Изумрудный"},{"Turquoise","Бирюзовый"},{"Sky blue","Голубой"},{"Indigo","Индиго"},{"Violet","Фиолетовый"},{"Rose","Розовый"},{"Coral","Коралловый"},{"Orange","Оранжевый"},{"Amber","Янтарный"}};
        for(String[] pair:accents)RU.put(pair[0],pair[1]);
        String[][] controls={{"LOCKED","БЛОКИРОВКА"},{"4×: lock","4×: блок"},{"Controls locked · 4× center to unlock","Кнопки заблокированы · 4× центр: снять"},{"4× center: unlock · Power / volume available","4× центр: снять · Питание / громкость доступны"},{"Seek","Перемотка"},{"Seek enabled · Center: volume · Hold: menu","Перемотка · Центр: звук · Удержание: меню"},{"Volume enabled · Center: seek · Hold: menu","Громкость · Центр: перемотка · Удержание: меню"},{"Keep turning to enable seek","Продолжайте круг для перемотки"},{"Keep turning to enable volume","Продолжайте круг для громкости"},{"Full turn: seek · Center: volume · Hold: menu","Полный круг: перемотка · Центр: звук · Удержание: меню"},{"Full turn: volume · Center: seek · Hold: menu","Полный круг: звук · Центр: перемотка · Удержание: меню"}};
        for(String[] pair:controls)RU.put(pair[0],pair[1]);
        String[][] extra={{"NOW PLAYING","СЕЙЧАС ИГРАЕТ"},{"UP NEXT","СЛЕДУЮЩИЙ"},{"Choose your music","Выберите музыку"},{"Hold center for library","Удерживайте центр: меню"},{"End of queue","Конец очереди"},{"Player menu","Меню плеера"},{"Library","Библиотека"},{"Download this track","Скачать этот трек"},{"Protect from cleanup","Защитить от очистки"},{"Allow rotation","Разрешить ротацию"},{"Cache policy","Очистка кэша"},{"Rotation profiles","Профили ротации"},{"Automatic cleanup","Автоочистка"},{"Audio cache limit","Лимит музыки"},{"Not listened for","Не прослушивалось"},{"Keep SD space free","Резерв на SD"},{"Clean now","Очистить сейчас"},{"Retry this track","Повторить загрузку"},{"Remove queued track","Убрать из очереди"},{"Previous page","Предыдущая страница"},{"Next page","Следующая страница"},{"No downloads yet","Нет загрузок"},{"Keep offline collection","Хранить всю коллекцию"},{"Daily rotation: 96 GiB / 90 days","Ротация: 96 ГиБ / 90 дней"},{"Small card: 2 GiB / 30 days","Малая карта: 2 ГиБ / 30 дней"}};
        for(String[] pair:extra)RU.put(pair[0],pair[1]);
        String[][] sync={{"Playlist synchronization","Синхронизация плейлистов"},{"Automatic playlist sync","Автосинхронизация"},{"Check every","Проверять каждые"},{"Check on charger connection","При подключении зарядки"},{"Check on charger removal","При отключении зарядки"},{"Check on home Wi-Fi connection","При подключении к домашнему Wi-Fi"},{"Offline playlists","Офлайн-плейлисты"},{"Check now","Проверить сейчас"},{"Keep playlist offline","Хранить плейлист офлайн"},{"Stop following updates","Не скачивать обновления"}};
        for(String[] pair:sync)RU.put(pair[0],pair[1]);
        String[][] labels={{"Playlists","Плейлисты"},{"Artists","Исполнители"},{"Albums","Альбомы"},{"Tracks","Треки"},{"Genres","Жанры"},{"Now Playing","Сейчас играет"},{"Settings","Настройки"},{"Device settings","Настройки плеера"},{"Force offline mode","Принудительный оффлайн"},{"Download queue","Очередь загрузок"},{"Resume / retry failed","Продолжить / повторить"},{"Pause downloads","Приостановить загрузки"},{"Clear download queue","Очистить очередь загрузок"},{"Play now","Воспроизвести сейчас"},{"Play next","Следом"},{"Add to playback queue","Добавить в очередь"},{"Download for offline","Скачать для оффлайна"},{"Remove from player","Удалить с плеера"},{"Details","Подробнее"},
        {"Bluetooth","Bluetooth"},{"Wi-Fi","Wi-Fi"},{"Screen timeout","Отключение экрана"},{"Brightness","Яркость"},{"Volume","Громкость"},{"Key lock (screen off)","Блокировка колеса"},{"Clicker","Звук колеса"},{"Vibration","Вибрация"},{"Timed power off","Таймер выключения"},{"Shuffle","Случайный порядок"},{"Repeat","Повтор"},{"Equalizer","Эквалайзер"},{"File extensions","Расширения файлов"},{"Battery percentage","Заряд в процентах"},{"Date / time","Дата и время"},{"Theme","Тема"},{"Wallpaper","Фон"},{"Language","Язык"},{"Track sorting","Сортировка"},{"Reset device settings","Сброс настроек плеера"},{"Clear downloaded audio","Удалить скачанную музыку"},{"About","О плеере"},{"Maintenance","Обслуживание"},{"Power off","Выключить плеер"},{"Back to main menu","Главное меню"},{"Back to playlists","К плейлистам"},{"Back to artists","К исполнителям"},{"Back to settings","К настройкам"},{"Back","Назад"},{"Off","Выключено"},{"On","Включено"},{"Cancel","Отмена"},{"Confirm","Подтвердить"},{"One track","Один трек"},{"All tracks","Все треки"},{"Always on","Не отключать"},{"10 seconds","10 секунд"},{"15 seconds","15 секунд"},{"30 seconds","30 секунд"},{"45 seconds","45 секунд"},{"1 minute","1 минута"},{"2 minutes","2 минуты"},{"5 minutes","5 минут"},{"10 minutes","10 минут"},{"20 minutes","20 минут"},{"30 minutes","30 минут"},{"60 minutes","60 минут"},{"90 minutes","90 минут"},{"120 minutes","120 минут"},
        {"Green","Зелёная"},{"Light","Светлая"},{"Blue","Синяя"},{"Natural ascending","Естественная, по возрастанию"},{"Natural descending","Естественная, по убыванию"},{"Alphabetical ascending","Алфавит, по возрастанию"},{"Alphabetical descending","Алфавит, по убыванию"},{"Year","Год"},{"Month","Месяц"},{"Day","День"},{"Hour","Час"},{"Minute","Минута"},{"Save date / time","Сохранить дату и время"},{"Clock format","Формат времени"},{"Time zone (UTC offset)","Часовой пояс (UTC)"},{"No wallpaper","Без фона"},{"Custom","Вручную"},{"Custom EQ","Эквалайзер вручную"},{"Use custom EQ","Применить эквалайзер"},{"12 hour","12 часов"},{"24 hour","24 часа"},{"Turn Wi-Fi off","Выключить Wi-Fi"},{"Turn Wi-Fi on","Включить Wi-Fi"},{"Turn Bluetooth off","Выключить Bluetooth"},{"Turn Bluetooth on","Включить Bluetooth"},{"Search for devices","Поиск устройств"},{"Stop searching","Остановить поиск"},{"Pair device","Сопряжение"},{"Connect audio","Подключить звук"},{"Disconnect audio","Отключить звук"},{"Forget device","Забыть устройство"},{"Sync playlist metadata","Синхронизировать плейлисты"},{"Save playlist audio offline","Скачать плейлист"},{"Refresh this playlist","Обновить плейлист"},{"Play / Pause","Играть / Пауза"},{"Previous","Предыдущий"},{"Next","Следующий"},{"Seek −15 seconds","Назад на 15 секунд"},{"Seek +15 seconds","Вперёд на 15 секунд"},{"Cancel download","Отменить скачивание"},
        {"Wheel: choose · Centre: apply · Back: cancel","Колесо: выбор · Центр: применить · Back: отмена"},{"Wheel: browse   Center: select   Back: main menu","Колесо: выбор · Центр: открыть · Back: меню"},{"Reset device preferences? Account and music stay.","Сбросить настройки? Музыка и аккаунт сохранятся."},{"Delete OnLoopio audio? Account and playlists stay.","Удалить музыку? Аккаунт и плейлисты сохранятся."},{"Open Android maintenance settings?","Открыть настройки Android?"},{"Power off the Y1?","Выключить Y1?"},{"Unknown artist","Неизвестный исполнитель"},{"Singles / unknown album","Без альбома"}};
        for(String[] pair:labels) RU.put(pair[0],pair[1]);
    }
    private static CharSequence translate(Context c,CharSequence value) {
        if(value==null || new DeviceSettings(c).number("language",0)==0) return value;
        String text=value.toString(),translated=RU.get(text); if(translated!=null) return translated;
        int colon=text.indexOf(": "); if(colon>0) { translated=RU.get(text.substring(0,colon)); if(translated!=null){ String suffix=text.substring(colon+2); String tail=RU.get(suffix); return translated+": "+(tail==null?suffix:tail); } }
        return value;
    }
}
