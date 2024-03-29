/**********
    Android ネットラジオ受信のための基本機能サンプルプログラム
   
    (C) 2015 INOUE Hirokazu
   
    Version 1.0 (2015/02/03)
    Version 1.1 (2015/10/12)
    Version 1.2 (2015/10/25)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *********/

package com.example.android_netradio_02;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Build;
import android.preference.PreferenceManager;

/**
 * MainAtivity
 */
public class MainActivity extends ActionBarActivity implements OnClickListener, OnCompletionListener,
OnErrorListener, OnInfoListener, Runnable {

    // 画面上のコンポーネント
    private Spinner spinner_stations;
    private Button button_play;
    private TextView text, text_time, text_uri;
    private static final int id_button_play = 0x1001;     // OnClickでの識別用
    private MediaPlayer media_player;
    // ネットラジオ局の「放送局名」と「URI」を格納する配列
    ArrayList<String> array_title = new ArrayList<String>();
    ArrayList<String> array_uri = new ArrayList<String>();
    // Intent での識別用
    final int REQUEST_PLS_FILE_READ = 0x1010;
    final int REQUEST_PREF_MENU = 0x1011;
    // スレッド用
    private Thread thread = null;
    private final Handler handler = new Handler();
    // 時刻表示スレッド停止命令フラグ
    private volatile boolean flag_thread_stop = false;
    // 再生経過秒数、表示フラグ
    private volatile boolean flag_pref_disp_time = false;
    private volatile long elapsed_time = System.currentTimeMillis()/1000L;
    private volatile boolean flag_disp_elapsed_time = false;
    // 停止タイマー
    private volatile boolean flag_timer = false;
    private volatile long timer_stop_time = 0;
    private volatile boolean flag_timer_quit = false;


    /** 
     * Called when the activity is starting
     * @see android.support.v7.app.ActionBarActivity#onCreate(android.os.Bundle)
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(get_pls_filename_from_preference() != null){
            // TitleとUriリストを一旦クリアする
            array_title.clear();
            array_uri.clear();
            // 指定されたファイルを読み込み、TitleとUriリストに格納する
            parse_pls_file(get_pls_filename_from_preference(), array_title, array_uri);
        }
        // スレッドで利用する経過時間表示フラグをプリファレンスから読み込む
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        flag_pref_disp_time = pref.getBoolean("pref_disp_time", false);
        // メディアプレーヤーの定義と、割込の設定
        media_player = new MediaPlayer();
        media_player.setOnCompletionListener(this);
        media_player.setOnErrorListener(this);
        media_player.setOnInfoListener(this);
        // 画面の定義と描画
        disp_main_screen();
        
        thread = new Thread(this);
        thread.start();
    }

    /**
     * Intentの結果を受け取る
     * @param requestCode   identify who this result came from
     * @param resultCode    returned by the child activity through its setResult()
     * @param data          return result data to the caller
     * @see android.support.v4.app.FragmentActivity#onActivityResult(int, int, android.content.Intent)
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data){
        // ファイル選択の結果を受け、PLSファイルを読み込んでTitleとUriをリストに格納する
        if(requestCode == REQUEST_PLS_FILE_READ){
            if(resultCode == Activity.RESULT_OK){
                String str_pls_filename = data.getData().getPath();
                Toast.makeText(MainActivity.this, "PLS = " + str_pls_filename, Toast.LENGTH_LONG).show();
                // TitleとUriリストを一旦クリアする
                array_title.clear();
                array_uri.clear();
                // 指定されたファイルを読み込み、TitleとUriリストに格納する
                parse_pls_file(str_pls_filename, array_title, array_uri);
                // 画面上のラジオ局選択Spinnerを更新する
                disp_update_spinner_stations(spinner_stations, array_title);
                // 画面上のメッセージを更新
                if(array_uri.size() <= 0) text.setText("Playlistが読み込まれていません");
                else{
                    text.setText(String.format("%d 局（曲）が選択可能\n次の操作を待機中", array_uri.size()));
                    save_preference(str_pls_filename);
                }
            }
        }
    }

    /**
     * PLS（プレイリスト）ファイルの読み込み
     */
    private void read_playlist_file(){
        // PLS（プレイリスト）形式ファイルの読み込み
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
//        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("file/*");
        startActivityForResult(intent, REQUEST_PLS_FILE_READ);
    }

    /**
     * PLS（プレイリスト）形式のファイルを読み込み、TitleとUriのリストに格納する
     * @param strPlsFilename    PLSファイルのフルパス（参照のみ）
     * @param array_title       放送局名を格納する配列（書き込みのみ）
     * @param array_uri         URIを格納する配列（書き込みのみ）
     */
    private static void parse_pls_file(String strPlsFilename, ArrayList<String> array_title, ArrayList<String> array_uri){
        String str_title = "", str_uri = "";
        Boolean flag_detect_header = false;
        try{
            FileInputStream file_input_stream = new FileInputStream(strPlsFilename);
            BufferedReader reader = new BufferedReader(new InputStreamReader(file_input_stream,"UTF-8"));
            String str_temp;
            while ((str_temp = reader.readLine() ) != null){
                if(str_temp.trim().equalsIgnoreCase("[playlist]")) flag_detect_header = true;
                else if(flag_detect_header){
                    String[] str_elem = str_temp.split("=");
                    if(str_elem.length != 2) continue;
                    if(str_elem[0].trim().toLowerCase(Locale.getDefault()).matches("^title.*")){
                        str_title =  str_elem[1].trim();
                    }
                    else if(str_elem[0].trim().toLowerCase(Locale.getDefault()).matches("^file.*")){
                        str_uri =  str_elem[1].trim();
                    }
                    else continue;
                    
                    if(str_uri.length() > 0 && str_title.length() > 0){
                        array_title.add(str_title);
                        array_uri.add(str_uri);
                        str_title = "";
                        str_uri = "";
                    }
                }
            }
            reader.close();
            file_input_stream.close();
        } catch(Exception e){
//            e.printStackTrace();
        }
    }

    /**
     * 画面の構築と表示
     */
    private void disp_main_screen(){
      LinearLayout layout = new LinearLayout(this);
      layout.setOrientation(LinearLayout.VERTICAL);
      // 画面の背景画像を指定（res/drawable-mdpi の background.jpg というファイルから読み込む）
      if(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)
          layout.setBackgroundResource(R.drawable.background);
      else
          layout.setBackgroundResource(R.drawable.background_land);
      setContentView(layout);

      // ラジオ局の選択Spinner
      spinner_stations = new Spinner(this);
      disp_update_spinner_stations(spinner_stations, array_title);
      spinner_stations.setBackgroundColor(Color.TRANSPARENT);
      layout.addView(spinner_stations);
      // 受信・停止ボタン
      button_play = new Button(this);
      if(media_player.isPlaying()) button_play.setText("停止");
      else button_play.setText("受信");
      button_play.setId(id_button_play);
      button_play.setBackgroundColor(Color.argb(125, 125, 125, 200));
      button_play.setTextColor(Color.WHITE);
      layout.addView(button_play);
      button_play.setOnClickListener(this);
      // 状況表示テキスト
      text = new TextView(this);
      text.setTextColor(Color.WHITE);
      layout.addView(text);

      if(media_player.isPlaying()) text.setText("受信中 ...");
      else {
          if(array_uri.size() <= 0) text.setText("Playlistが読み込まれていません");
          else text.setText(String.format("%d 局（曲）が選択可能\n次の操作を待機中", array_uri.size()));
      }
      // 時刻表示テキスト
      text_time = new TextView(this);
      text_time.setTextColor(Color.WHITE);
      layout.addView(text_time);
      // Uri表示テキスト
      text_uri = new TextView(this);
      text_uri.setTextColor(Color.GRAY);
      layout.addView(text_uri);
    }

    /**
     * ラジオ局選択Spinnerを、与えられたTitleリストで更新する
     * @param spinner       画面上の放送局選択のスピナー（コントロールに値をセット）
     * @param arr           放送局名を格納した配列（参照のみ）
     */
    private void disp_update_spinner_stations(Spinner spinner, ArrayList<String> arr){
        // TitleリストをArrayAdapterにセットする
        ArrayAdapter<String> array_adapter = new ArrayAdapter<String>(this,
//                android.R.layout.simple_spinner_item,
                R.layout.spinner_item,          // XMLで外観を設定
                (String[]) arr.toArray(new String[array_title.size()]));
        array_adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Spinnerの内容を書き換える
        spinner.setAdapter(array_adapter);
    }

    /**
     * PLSファイル名をpreferenceに保存する処理
     * @param pls_filename      PLSファイルのフルパス（参照のみ）
     */
    private void save_preference(String pls_filename){
        SharedPreferences pref = getSharedPreferences("pref", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.putString("PlsFilename", pls_filename);
        editor.commit();
    }

    /**
     * PLSファイル名をpreferenceから読み出す処理
     * @return      PLSファイルのフルパス
     */
    private String get_pls_filename_from_preference(){
        SharedPreferences pref = getSharedPreferences("pref", Context.MODE_PRIVATE);
        return pref.getString("PlsFilename", null);
    }

    /**
     * 画面タップの割込  Called when a view has been clicked
     * @see android.view.View.OnClickListener#onClick(android.view.View)
     * @param v     The view that was clicked
     */
    @Override
    public void onClick(View v) {
        // 再生・停止ボタンが押された時の処理
        if(v.getId() == id_button_play){
            if(array_uri.size() <= 0){
                text.setText("PlayListが読み込まれていないため、選局不能");
            }
            if(!media_player.isPlaying()){
                // 再生していない場合は、受信開始
                mediaplayer_start();
            }
            else {
                try{
                    // 経過時刻表示の無効化
                    flag_disp_elapsed_time = false;
                    // 受信停止
                    media_player.stop();
                    // ボタン表示の更新
                    button_play.setText("受信");
                    // テキスト、Uri表示テキストの更新
                    text.setText("次の操作を待機中");
                    text_uri.setText("");
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "media_player.stopエラー", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * ネットラジオの受信開始処理
     */
    private void mediaplayer_start(){
        Toast.makeText(MainActivity.this, "接続中 ...", Toast.LENGTH_LONG).show();
        // スレッドで利用する経過時間表示フラグをプリファレンスから読み込む
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        flag_pref_disp_time = pref.getBoolean("pref_disp_time", false);
        // 経過時間表示をクリア
        text_time.setText("");
        try{
            // メディアプレーヤーの受信処理
            media_player.reset();
            media_player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            media_player.setDataSource(array_uri.get(spinner_stations.getSelectedItemPosition()));
    //      setDataSourceの引数形式は "file://mnt/sdcard/test.mp3", "http://example.com/test"
            media_player.prepare();
            media_player.start();
            // ボタン表示の更新
            button_play.setText("停止");
            // テキストの更新
            String strDisp = "受信中 ...";
            if(media_player.getDuration() > 0)
                strDisp += String.format("\n曲長 %d 秒", media_player.getDuration()/1000);
            text.setText(strDisp);
            // Uri表示テキストの更新
            if(pref.getBoolean("pref_disp_uri", false))
                text_uri.setText(array_uri.get(spinner_stations.getSelectedItemPosition()));
            // 経過時刻表示の有効化
            elapsed_time = System.currentTimeMillis()/1000L;
            flag_disp_elapsed_time = true;
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "接続失敗", Toast.LENGTH_LONG).show();
            button_play.setText("受信");
            text.setText("接続に失敗。次の操作を待機中\n code:"+e.toString());
        }
    }

    /**
     * 時刻表示スレッドのメインループ  be called in that separately executing thread
     * @see java.lang.Runnable#run()
     */
    public void run() {
        while (!flag_thread_stop) {
            // 500 ミリ秒待機
            try { Thread.sleep(500); }
            catch (InterruptedException e) {
                // interrupt() がコールされた場合の処理
                flag_thread_stop = true;
            }

            if(flag_disp_elapsed_time && flag_pref_disp_time){
                // UI widgetへのアクセスは Runnable() 内で行う
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 現在時刻
                        int min = (int) ((System.currentTimeMillis()/1000L - elapsed_time) / 60L);
                        int sec = (int) ((System.currentTimeMillis()/1000L - elapsed_time) % 60L);
                        // タイマーが働いている場合の処理
                        if(flag_timer){
                            int remain = (int) (timer_stop_time - System.currentTimeMillis()/1000L);
                            // タイマー時間が経過・完了した場合
                            if(remain < 0){
                                flag_timer = false;
                                // メディアプレーヤが演奏中の場合は停止する
                                if(media_player.isPlaying()){
                                    try{
                                        media_player.stop();
                                    } catch (Exception e) {
                                    }
                                }
                                // 表示を更新
                                flag_disp_elapsed_time = false;
                                text.setText("");
                                text_time.setText("タイマーで停止しました");
                                text_uri.setText("");
                                button_play.setText("受信");
                                // タイマー経過後プログラム終了
                                if(flag_timer_quit){
                                    quit_program();
                                }
                            }
                            // タイマーの残り時間と、再生開始からの経過時間を表示する
                            else {
                                text_time.setText(String.format("%02d:%02d (残り%d分%02d秒)", min, sec, remain/60, remain%60));
                            }
                        }
                        // タイマーが働いていない場合は、再生開始からの経過時間を表示
                        else{
                            text_time.setText(String.format("%02d:%02d", min, sec));
                        }
                    }
                });
            }
        }
    }

    /**
     * 時刻表示スレッドの停止
     */
    private void stop_thread(){
        if(thread.isAlive()) 
        {
            thread.interrupt();
            // スレッドの終了を待つ
            try{ thread.join(); }
            catch(InterruptedException e) { }
        }
    }

    /**
     * プログラムを終了する（再生停止、スレッド破棄含む）
     */
    private void quit_program(){
        // メディアプレーヤが演奏中の場合は停止する
        if(media_player.isPlaying()){
            try{
                media_player.stop();
            } catch (Exception e) {
            }
        }
        // 時刻表示・タイマー スレッドの停止
        stop_thread();
        // 自身のプロセスを停止する
        this.finish();  // プログラムが全面で画面sleepの場合、プログラムが再起動するのを防ぐ
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(1); // killProcessで終了しているため、この行には到達しない
    }

    /**
     * メニューから呼びだされるタイマー設定のAlertDialog
     */
    private void set_timer() {
        // 選択肢リストを作成する
        final String[] items = new String[12];
        for(int i=0; i<12; i++) {
            items[i] = String.format("%d 分後", (i+1)*5);
        }
        // AlertDialogを構築する
        final AlertDialog.Builder dlg = new AlertDialog.Builder(MainActivity.this);
        dlg.setTitle("リストのAlertDialog");
        dlg.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int idx) {
                Toast.makeText(MainActivity.this, String.format("%s に受信停止", items[idx]), Toast.LENGTH_LONG).show();
                // タイマーのセット
                flag_timer = true;
                timer_stop_time = System.currentTimeMillis()/1000L + (idx + 1) * 5 * 60;
            }
        });
        dlg.setNegativeButton("キャンセル", null);
        // AlertDialogを表示する
        dlg.show();
        
        // タイマー経過後、プログラムを終了するかの設定をPreferenceより読み込む
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        flag_timer_quit = pref.getBoolean("pref_timer_quit", false);
    }
    
    /**
     * Initialize the contents of the Activity's standard options menu
     * @see android.app.Activity#onCreateOptionsMenu(android.view.Menu)
     * @param menu      The options menu in which you place your items
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    /**
     * メニュー選択による分岐  This hook is called whenever an item in your options menu is selected
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     * @param item      The menu item that was selected
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        // 終了メニューが選択された場合
        if (id == R.id.menu_exit) {
            quit_program();
            return true;
        }
        // Playlistファイルの読込が選択された場合
        else if(id == R.id.menu_read_pls_file){
            read_playlist_file();
            return true;
        }
        // 機能設定が選択された場合
        else if(id == R.id.menu_disp_pref){
            // SettingsMenuクラスを呼び出して、プリファレンスメニューの初期化と表示を行う
            Intent intent = new Intent(this, SettingMenu.class);
            startActivityForResult(intent, REQUEST_PREF_MENU);
            return true;
        }
        // タイマーが選択された場合
        else if(id == R.id.menu_timer){
            // タイマー未起動の場合、設定ダイアログを表示
            if(!flag_timer){
                set_timer();
            }
            // タイマー起動中の場合、無効化する
            else{
                flag_timer = false;
                Toast.makeText(MainActivity.this, "タイマーを停止しました", Toast.LENGTH_LONG).show();
            }
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 曲再生が末端に達した時の割り込み Called when the end of a media source is reached during playback
     * @see android.media.MediaPlayer.OnCompletionListener#onCompletion(android.media.MediaPlayer)
     * @param mp    対象となるMediaPlayer
     */
    @Override
    public void onCompletion(MediaPlayer mp) {
        // メディアプレーヤーによる受信を停止し、接続を閉じる
        mp.reset();
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        if(pref.getBoolean("pref_end_restart", false)){
            Toast.makeText(MainActivity.this, "終了信号受信。再受信処理中", Toast.LENGTH_LONG).show();
            mediaplayer_start();
        }
        else{
            // 経過時刻表示の無効化
            flag_disp_elapsed_time = false;
            button_play.setText("受信");
            text.setText("再生終了。次の操作を待機中");
        }
    }

    /**
     * MediaPlayerでエラーが発生した時の割り込み
     * @see android.media.MediaPlayer.OnErrorListener#onError(android.media.MediaPlayer, int, int)
     * @param mp        対象となるMediaPlayer
     */
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        // 経過時刻表示の無効化
        flag_disp_elapsed_time = false;
        // メディアプレーヤーのリセット（停止）
        mp.reset();
        button_play.setText("受信");
        text.setText("エラー。次の操作を待機中");
        return false;
    }

    /**
     * MediaPlayerから警告等が発せられた場合の割込処理
     * @see android.media.MediaPlayer.OnInfoListener#onInfo(android.media.MediaPlayer, int, int)
     * @param mp        対象となるMediaPlayer
     */
    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if(what == MediaPlayer.MEDIA_INFO_BUFFERING_START){
            text.setText("バッファリング中 ...");
        }
        else if(what == MediaPlayer.MEDIA_INFO_BUFFERING_END){
            text.setText("受信中 ...");
        }
        else{
            text.setText("受信が不安定です ...");
        }
        return false;
    }

    /**
     * Activityが破棄される時（「戻るキー」でタスク自体は残っている場合も含む）
     * @see android.support.v4.app.FragmentActivity#onDestroy()
     */
    @Override
    protected void onDestroy() {
        // メディアプレーヤが演奏中の場合は停止する
        if(media_player.isPlaying()){
            try{
                media_player.stop();
                media_player.reset();
            } catch (Exception e) {
            }
        }
        // 時刻表示スレッドの停止
        stop_thread();
        super.onDestroy();
    }
}
