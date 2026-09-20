package pt.casafotos.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

public class ViewerActivity extends Activity {
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    File original;JSONObject asset;LinearLayout root;FrameLayout preview;TextView status;Button save;VideoView video;
    boolean saved=false;
    @Override public void onCreate(Bundle b){super.onCreate(b);
        try{asset=new JSONObject(getIntent().getStringExtra("asset"));}catch(Exception e){finish();return;}
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(16,16,16,16);root.setBackgroundColor(0xfff7f8f4);
        root.setOnApplyWindowInsetsListener((v,i)->{Insets bars=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(16+bars.left,16+bars.top,16+bars.right,16+bars.bottom);return i;});setContentView(root);
        Button back=new Button(this);back.setText("Voltar à biblioteca");back.setAllCaps(false);back.setOnClickListener(v->finish());root.addView(back);
        TextView title=new TextView(this);title.setText(asset.optString("name"));title.setTextColor(0xff17352f);title.setTextSize(19);root.addView(title);
        status=new TextView(this);status.setText("A abrir o original…");root.addView(status);preview=new FrameLayout(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        save=new Button(this);save.setAllCaps(false);save.setText("Guardar uma cópia na galeria");save.setEnabled(false);save.setOnClickListener(v->saveCopy());root.addView(save);
        File folder=new File(getCacheDir(),"viewer");folder.mkdirs();File[] old=folder.listFiles();if(old!=null)for(File f:old)f.delete();
        original=new File(folder,asset.optString("id")+asset.optString("extension"));
        worker.execute(()->{try{new Net(this).download("/assets/"+asset.getString("id")+"/original",original,asset.getString("id"));runOnUiThread(()->showOriginal());}catch(Exception e){runOnUiThread(()->status.setText(MainActivity.explain(e)));}});
    }
    void showOriginal(){if(isFinishing()||isDestroyed()){original.delete();return;}status.setText("Original verificado · "+String.format(java.util.Locale.ROOT,"%.1f MB",original.length()/1e6));save.setEnabled(true);
        if(asset.optString("mime").startsWith("video/")){video=new VideoView(this);preview.addView(video,new FrameLayout.LayoutParams(-1,-1));MediaController controller=new MediaController(this);controller.setAnchorView(video);video.setMediaController(controller);video.setVideoURI(Uri.fromFile(original));video.setOnPreparedListener(p->video.start());video.setOnErrorListener((p,w,e)->{status.setText("Este vídeo não é compatível com o leitor do telemóvel. Podes guardar o original na galeria.");return true;});}
        else{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(original.toString(),options);options.inSampleSize=1;while(options.outWidth/options.inSampleSize>2400||options.outHeight/options.inSampleSize>2400)options.inSampleSize*=2;options.inJustDecodeBounds=false;
            Bitmap bitmap=BitmapFactory.decodeFile(original.toString(),options);
            if(bitmap!=null){try{android.media.ExifInterface exif=new android.media.ExifInterface(original.toString());int orientation=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);Matrix m=new Matrix();switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(-90);m.postScale(-1,1);break;case 8:m.setRotate(-90);break;}if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}}catch(Exception ignored){}
                ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(bitmap);preview.addView(image,new FrameLayout.LayoutParams(-1,-1));}
            else status.setText("Pré-visualização indisponível neste telemóvel. O original está guardado e podes copiá-lo para a galeria.");}
    }
    void saveCopy(){if(saved)return;save.setEnabled(false);status.setText("A guardar na galeria…");worker.execute(()->{
        Uri item=null;try{
            if(original.length()>getFilesDir().getUsableSpace()-32*1024*1024)throw new IOException("Falta espaço no telemóvel.");
            boolean isVideo=asset.getString("mime").startsWith("video/");ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,asset.getString("name"));values.put(MediaStore.MediaColumns.MIME_TYPE,asset.getString("mime"));values.put(MediaStore.MediaColumns.RELATIVE_PATH,isVideo?"Movies/CasaFotos":"Pictures/CasaFotos");values.put(MediaStore.MediaColumns.IS_PENDING,1);
            item=getContentResolver().insert(isVideo?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(item==null)throw new IOException("Não foi possível criar a cópia.");
            try(InputStream in=new FileInputStream(original);OutputStream out=getContentResolver().openOutputStream(item)){if(out==null)throw new IOException("Não foi possível guardar.");byte[] buffer=new byte[256*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
            try(InputStream in=getContentResolver().openInputStream(item)){if(in==null||!asset.getString("id").equals(Net.hash(in)))throw new IOException("A cópia não passou na verificação.");}
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(item,values,null,null);saved=true;runOnUiThread(()->{status.setText("Cópia guardada na galeria. O original mantém-se na aplicação.");save.setText("Guardado na galeria");});
        }catch(Exception e){if(item!=null)getContentResolver().delete(item,null,null);runOnUiThread(()->{status.setText(MainActivity.explain(e));save.setEnabled(true);});}
    });}
    @Override protected void onPause(){super.onPause();if(video!=null)video.pause();}
    @Override protected void onDestroy(){super.onDestroy();if(video!=null)video.stopPlayback();worker.execute(()->{if(original!=null)original.delete();});worker.shutdown();}
}
