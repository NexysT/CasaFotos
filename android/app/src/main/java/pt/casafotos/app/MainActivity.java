package pt.casafotos.app;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    static final int GREEN=0xff176a59, INK=0xff17352f, MUTED=0xff61766f, PAPER=0xfff7f8f4, SOFT=0xffe3eee5, LINE=0xffd9e4db;
    final ExecutorService work=Executors.newSingleThreadExecutor();
    final ExecutorService images=Executors.newFixedThreadPool(3);
    final AtomicBoolean busy=new AtomicBoolean(false);
    final Handler handler=new Handler(Looper.getMainLooper());
    LinearLayout root; TextView connection, capacity, progress, heading; ProgressBar spaceBar;
    GridView grid; Button more; EditText search;
    ArrayList<JSONObject> assets=new ArrayList<>();
    JSONArray queue=new JSONArray();
    String filter=""; int nextOffset=-1; boolean promptOpen=false, resumed=false, loading=false;
    ArrayList<String> deleting=new ArrayList<>();
    Net net;
    final Runnable retry=new Runnable(){public void run(){if(resumed){if(net.ready()&&!busy.get())processQueue();handler.postDelayed(this,30000);}}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);net=new Net(this);
        try{queue=new JSONArray(read(new File(getFilesDir(),"queue.json")));}catch(Exception ignored){}
        try{JSONArray a=new JSONArray(read(new File(getFilesDir(),"gallery.json")));for(int i=0;i<a.length();i++)assets.add(a.getJSONObject(i));}catch(Exception ignored){}
        // Uma interrupção durante o diálogo do Android nunca autoriza outra remoção.
        synchronized(this){for(int i=0;i<queue.length();i++){JSONObject q=queue.optJSONObject(i);if("delete_requested".equals(q.optString("state")))put(q,"state","kept");}saveQueue();}
        build();if(!net.ready())setup();
    }
    @Override protected void onResume(){super.onResume();resumed=true;handler.postDelayed(retry,30000);if(net!=null&&net.ready()){refresh(false);processQueue();}}
    @Override protected void onPause(){super.onPause();resumed=false;handler.removeCallbacks(retry);}
    @Override protected void onDestroy(){super.onDestroy();handler.removeCallbacksAndMessages(null);images.shutdownNow();work.shutdown();}
    int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    TextView label(String text,int sp,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(sp);t.setTextColor(color);return t;}
    Button button(String title,View.OnClickListener action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(GREEN);b.setTextSize(13);b.setOnClickListener(action);return b;}
    Button mainButton(String title,View.OnClickListener action){Button b=button(title,action);b.setTextColor(0xffffffff);b.setTypeface(null,Typeface.BOLD);b.setBackground(rounded(GREEN));b.setPadding(dp(13),0,dp(13),0);return b;}
    GradientDrawable bordered(int color){GradientDrawable d=rounded(color);d.setStroke(dp(1),LINE);return d;}
    GradientDrawable rounded(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));return d;}
    void build(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(PAPER);root.setPadding(dp(18),dp(14),dp(18),dp(10));
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(18)+bars.left,dp(14)+bars.top,dp(18)+bars.right,dp(10)+bars.bottom);return insets;});
        setContentView(root);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity=new LinearLayout(this);identity.setOrientation(1);
        TextView eyebrow=label("A BIBLIOTECA DA FAMÍLIA",10,GREEN);eyebrow.setTypeface(null,Typeface.BOLD);eyebrow.setLetterSpacing(.12f);identity.addView(eyebrow);
        TextView brand=label("CasaFotos",31,INK);brand.setTypeface(null,Typeface.BOLD);identity.addView(brand);
        top.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        Button connect=button("⚙  Ligação",v->setup());connect.setBackground(bordered(0xffffffff));top.addView(connect,new LinearLayout.LayoutParams(dp(100),dp(46)));
        root.addView(top);
        TextView tagline=label("As tuas memórias, guardadas em casa.",14,MUTED);tagline.setPadding(0,dp(4),0,0);root.addView(tagline);

        LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(18),dp(16),dp(18),dp(17));card.setBackground(rounded(SOFT));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(21),0,dp(14));root.addView(card,cp);
        TextView small=label("ARMAZENAMENTO PRIVADO",11,GREEN);small.setLetterSpacing(.09f);small.setTypeface(null,Typeface.BOLD);card.addView(small);
        connection=label("A ligar à tua biblioteca…",17,INK);connection.setTypeface(null,Typeface.BOLD);connection.setPadding(0,dp(10),0,dp(5));card.addView(connection);
        capacity=label("A consultar espaço disponível…",13,MUTED);card.addView(capacity);
        spaceBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);spaceBar.setMax(1000);spaceBar.setProgress(0);spaceBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));spaceBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffcad8ce));
        LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,dp(6));barParams.setMargins(0,dp(12),0,0);card.addView(spaceBar,barParams);

        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        Button add=mainButton("+  Adicionar fotos",v->pick());actions.addView(add,new LinearLayout.LayoutParams(0,dp(53),1));
        Button update=button("Atualizar",v->{refresh(false);processQueue();});update.setBackground(bordered(0xffffffff));LinearLayout.LayoutParams updateParams=new LinearLayout.LayoutParams(dp(112),dp(53));updateParams.setMargins(dp(9),0,0,0);actions.addView(update,updateParams);root.addView(actions);
        progress=label("Adiciona fotografias ou vídeos do telemóvel.",12,MUTED);progress.setPadding(dp(3),dp(12),0,dp(16));progress.setOnClickListener(v->queueDialog());root.addView(progress);
        search=new EditText(this);search.setSingleLine();search.setTextSize(14);search.setHint("⌕  Procurar fotografias ou álbuns");search.setPadding(dp(16),0,dp(16),0);search.setBackground(bordered(0xffffffff));
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int before,int count){filter=s.toString().toLowerCase(Locale.ROOT);showGrid();}public void afterTextChanged(android.text.Editable e){}});
        root.addView(search,new LinearLayout.LayoutParams(-1,dp(51)));
        heading=label("A tua biblioteca",19,INK);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(dp(2),dp(20),0,dp(10));root.addView(heading);
        grid=new GridView(this);grid.setNumColumns(3);grid.setHorizontalSpacing(dp(7));grid.setVerticalSpacing(dp(7));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setSelector(android.R.color.transparent);root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        more=button("Carregar mais fotografias",v->refresh(true));more.setVisibility(View.GONE);root.addView(more);showGrid();
    }
    void message(String title,String text){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Está bem",null).show();}
    void status(String text){runOnUiThread(()->{if(!isDestroyed())progress.setText(text);});}
    void setup(){
        if(busy.get()){message("Transferência em curso","Aguarda que termine antes de alterar a ligação.");return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(24),dp(8),dp(24),0);
        box.addView(label("Abre o painel CasaFotos no PC e carrega em Ligar telemóvel. Usa a mesma rede Wi-Fi.",15,MUTED));
        EditText address=new EditText(this);address.setSingleLine();address.setHint("192.168.1.10:47831");address.setText(net.base.replace("https://",""));box.addView(address);
        new AlertDialog.Builder(this).setTitle("Ligar a tua biblioteca").setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Continuar",(d,w)->{
            status("A procurar o PC…");work.execute(()->{try{String base=Net.address(address.getText().toString());String pin=Net.probe(base);runOnUiThread(()->confirmIdentity(base,pin));}
                catch(Exception e){status("PC indisponível. Confirma o endereço e a rede Wi-Fi.");runOnUiThread(()->message("Não foi possível ligar",explain(e)));}});
        }).show();
    }
    void confirmIdentity(String base,String pin){
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(24),0,dp(24),0);
        box.addView(label("Confirma que a identificação no painel do PC é igual:",15,INK));
        TextView fingerprint=label(pin.substring(0,16).toUpperCase(Locale.ROOT),23,GREEN);fingerprint.setTypeface(Typeface.MONOSPACE);fingerprint.setPadding(0,dp(12),0,dp(12));box.addView(fingerprint);
        EditText code=new EditText(this);code.setHint("Código de 8 algarismos do PC");code.setInputType(2);code.setSingleLine();box.addView(code);
        new AlertDialog.Builder(this).setTitle("Confirmar ligação").setView(box).setNegativeButton("Cancelar",null).setPositiveButton("É igual, ligar",(d,w)->work.execute(()->{
            try{
                JSONObject result=new Net(base,pin,"").json("/pair",new JSONObject().put("code",code.getText().toString().trim()));
                if(!getSharedPreferences("connection",0).edit().putString("base",base).putString("pin",pin).putString("token",result.getString("token")).commit())throw new IOException("Não foi possível guardar a ligação.");
                net=new Net(this);runOnUiThread(()->{status("Biblioteca ligada.");refresh(false);processQueue();});
            }catch(Exception e){runOnUiThread(()->message("Ligação não concluída",explain(e)));}
        })).show();
    }
    void pick(){
        if(!net.ready()){setup();return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,10);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==10&&result==RESULT_OK&&data!=null){
            ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());
            synchronized(this){
                for(Uri uri:uris){try{
                    boolean exists=false;for(int n=0;n<queue.length();n++){JSONObject q=queue.getJSONObject(n);if(uri.toString().equals(q.optString("uri"))&&Arrays.asList("pending","stored","delete_requested").contains(q.optString("state")))exists=true;}if(exists)continue;
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    String name="fotografia";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
                    String mime=getContentResolver().getType(uri);if(mime==null||!(mime.startsWith("image/")||mime.startsWith("video/")))continue;
                    queue.put(new JSONObject().put("uri",uri.toString()).put("name",name).put("mime",mime).put("state","pending"));
                }catch(Exception e){message("Não foi possível adicionar","Seleciona um ficheiro local através de Fotografias ou Vídeos. "+explain(e));}}
                saveQueue();
            }
            processQueue();
        }
        if(request==20){
            synchronized(this){for(int i=0;i<queue.length();i++){JSONObject q=queue.optJSONObject(i);if(deleting.contains(q.optString("uri"))){put(q,"state",result==RESULT_OK?"removed":"kept");release(q);}}saveQueue();}
            deleting.clear();promptOpen=false;status(result==RESULT_OK?"Originais removidos do telemóvel. Fotografias guardadas na aplicação.":"As fotografias continuam nos dois sítios.");
        }
    }
    void release(JSONObject q){try{getContentResolver().releasePersistableUriPermission(Uri.parse(q.getString("uri")),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}
    synchronized ArrayList<JSONObject> jobs(String state){ArrayList<JSONObject> result=new ArrayList<>();for(int i=0;i<queue.length();i++){JSONObject q=queue.optJSONObject(i);if(state.equals(q.optString("state")))result.add(q);}return result;}
    synchronized void state(JSONObject q,String state){put(q,"state",state);saveQueue();}
    synchronized void saveQueue(){try{write(new File(getFilesDir(),"queue.json"),queue.toString());}catch(Exception e){throw new IllegalStateException("Não foi possível guardar a lista de transferências.",e);}}
    static void put(JSONObject q,String key,Object v){try{q.put(key,v);}catch(JSONException e){throw new IllegalStateException(e);}}
    static String read(File f)throws Exception{try(FileInputStream in=new AtomicFile(f).openRead()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    static void write(File f,String text)throws Exception{AtomicFile a=new AtomicFile(f);FileOutputStream out=null;try{out=a.startWrite();out.write(text.getBytes("UTF-8"));a.finishWrite(out);}catch(Exception e){if(out!=null)a.failWrite(out);throw e;}}
    static String explain(Exception e){if(e instanceof javax.net.ssl.SSLException)return "Não foi possível confirmar a identidade do PC. Confirma a data dos dispositivos e volta a ligar pelo painel.";if(e instanceof java.net.ConnectException||e instanceof java.net.SocketTimeoutException||e instanceof java.net.UnknownHostException)return "PC indisponível. Liga-o e confirma que estás na mesma rede Wi-Fi.";return e.getMessage()==null?"Não foi possível concluir a operação.":e.getMessage();}
    void processQueue(){
        if(!net.ready()||promptOpen||!busy.compareAndSet(false,true))return;
        work.execute(()->{
            boolean transferred=false;
            try{
                for(JSONObject q:jobs("pending")){
                    try{
                        net.json("/stats",null); // falhar depressa quando o PC está desligado
                        Uri uri=Uri.parse(q.getString("uri"));status("A preparar: "+q.getString("name"));
                        MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
                        try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Original indisponível.");byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){digest.update(b,0,n);size+=n;}}
                        String hash=Net.hex(digest.digest());final long total=size;final long[] last={0};
                        synchronized(this){q.put("hash",hash).put("size",size);saveQueue();}
                        JSONObject receipt=net.upload(getContentResolver().openInputStream(uri),size,hash,q.getString("name"),q.getString("mime"),bytes->{long now=System.currentTimeMillis();if(now-last[0]>500){last[0]=now;status("A guardar: "+q.optString("name")+" · "+(total==0?0:bytes*100/total)+"%");}});
                        if(!receipt.optBoolean("verified")||!hash.equals(receipt.optString("sha256"))||size!=receipt.optLong("size"))throw new IOException("Não foi possível confirmar o ficheiro. O original foi mantido.");
                        state(q,"stored");transferred=true;
                    }catch(Exception e){put(q,"error",explain(e));saveQueue();status("A aguardar · "+explain(e)+" Toca aqui para gerir.");break;}
                }
            }finally{
                busy.set(false);boolean changed=transferred;runOnUiThread(()->{if(changed)refresh(false);askRemove();});
            }
        });
    }
    void askRemove(){
        if(!resumed||promptOpen||isFinishing())return;
        ArrayList<JSONObject> saved=jobs("stored");if(saved.isEmpty())return;promptOpen=true;
        new AlertDialog.Builder(this).setTitle("Fotografias guardadas")
            .setMessage(saved.size()+" ficheiro(s) ficaram guardados e verificados na aplicação.\n\nQueres remover estes originais da galeria do telemóvel para libertar espaço?")
            .setNegativeButton("Não, manter",(d,w)->keep(saved))
            .setPositiveButton("Sim, remover",(d,w)->prepareDelete(saved))
            .setOnCancelListener(d->keep(saved)).show();
    }
    void keep(ArrayList<JSONObject> saved){for(JSONObject q:saved){state(q,"kept");release(q);}promptOpen=false;status("Fotografias guardadas na aplicação e mantidas no telemóvel.");}
    void prepareDelete(ArrayList<JSONObject> saved){
        if(!busy.compareAndSet(false,true)){promptOpen=false;message("Aguarda um momento","Existe uma transferência em curso. Voltaremos a perguntar quando terminar.");return;}
        status("A confirmar os originais antes de remover…");work.execute(()->{
            ArrayList<Uri> media=new ArrayList<>();ArrayList<JSONObject> approved=new ArrayList<>();int skipped=0;
            try{
                for(JSONObject q:saved){
                    try{
                        Uri source=Uri.parse(q.getString("uri"));String hash=q.getString("hash");
                        JSONObject proof=net.json("/assets/"+hash+"/verify",new JSONObject());
                        if(!proof.optBoolean("verified")||!hash.equals(proof.optString("sha256"))||proof.optLong("size")!=q.getLong("size"))throw new IOException("A cópia não passou na verificação.");
                        try(InputStream in=getContentResolver().openInputStream(source)){if(in==null||!hash.equals(Net.hash(in)))throw new IOException("O original foi alterado.");}
                        Uri item="media".equals(source.getAuthority())?source:MediaStore.getMediaUri(this,source);
                        // ExternalStorageProvider pode devolver a coleção genérica Files.
                        // O pedido Android de remoção requer a coleção específica de imagens/vídeos.
                        if(item!=null&&"media".equals(item.getAuthority())&&item.getPath().matches("/[^/]+/file/[0-9]+")){
                            String volume=item.getPathSegments().get(0);long id=ContentUris.parseId(item);
                            Uri collection=q.getString("mime").startsWith("video/")?MediaStore.Video.Media.getContentUri(volume):MediaStore.Images.Media.getContentUri(volume);
                            item=ContentUris.withAppendedId(collection,id);
                        }
                        if(item==null||!"media".equals(item.getAuthority())||!(item.getPath().matches("/[^/]+/(images|video)/media/[0-9]+")))throw new IOException("Este fornecedor não permite remover pela aplicação.");
                        media.add(item);approved.add(q);
                    }catch(Exception e){skipped++;state(q,"kept");release(q);}
                }
                final int notRemoved=skipped;
                runOnUiThread(()->{
                    busy.set(false);
                    if(media.isEmpty()){promptOpen=false;message("Originais mantidos","Não foi possível confirmar e pedir a remoção destes originais. Permanecem no telemóvel. Se vieram de outro serviço ou de uma pasta, remove-os manualmente apenas depois de confirmares as cópias.");return;}
                    try{
                        // O sistema mostra a confirmação obrigatória. Nunca se chama delete() no original.
                        PendingIntent request=MediaStore.createDeleteRequest(getContentResolver(),media);
                        deleting.clear();for(JSONObject q:approved){deleting.add(q.optString("uri"));state(q,"delete_requested");}
                        if(notRemoved>0)Toast.makeText(this,notRemoved+" original(is) serão mantidos.",Toast.LENGTH_LONG).show();
                        startIntentSenderForResult(request.getIntentSender(),20,null,0,0,0);
                    }catch(Exception e){for(JSONObject q:approved)state(q,"kept");promptOpen=false;message("Originais mantidos",explain(e));}
                });
            }catch(Exception e){busy.set(false);runOnUiThread(()->{promptOpen=false;message("Nada foi removido",explain(e));});}
        });
    }
    void queueDialog(){
        ArrayList<JSONObject> pending=jobs("pending");StringBuilder text=new StringBuilder();
        for(JSONObject q:pending)text.append(q.optString("name")).append("\n").append(q.optString("error","A aguardar")).append("\n\n");
        if(pending.isEmpty()){message("Transferências","Não há transferências pendentes.");return;}
        new AlertDialog.Builder(this).setTitle(pending.size()+" pendente(s)").setMessage(text.toString())
            .setPositiveButton("Tentar novamente",(d,w)->processQueue()).setNegativeButton("Fechar",null)
            .setNeutralButton("Cancelar pendentes",(d,w)->{if(busy.get()){message("Transferência em curso","Aguarda que termine.");return;}for(JSONObject q:pending){state(q,"cancelled");release(q);}status("Pendentes cancelados. Nada foi removido do telemóvel.");}).show();
    }
    void refresh(boolean append){
        if(!net.ready()||loading)return;loading=true;
        work.execute(()->{try{
            JSONObject stats=net.json("/stats",null);JSONObject result=net.json("/assets?offset="+(append?Math.max(0,nextOffset):0),null);
            JSONArray items=result.getJSONArray("items");ArrayList<JSONObject> incoming=new ArrayList<>();for(int i=0;i<items.length();i++)incoming.add(items.getJSONObject(i));
            int offset=result.isNull("next_offset")?-1:result.getInt("next_offset");
            runOnUiThread(()->{
                if(!append)assets.clear();assets.addAll(incoming);nextOffset=offset;
                connection.setText("● Biblioteca disponível");capacity.setText(String.format(Locale.forLanguageTag("pt-PT"),"%.2f de 30 GB utilizados · %d ficheiros",stats.optLong("used_bytes")/1e9,stats.optInt("count")));spaceBar.setProgress((int)Math.min(1000,stats.optLong("used_bytes")*1000.0/30_000_000_000L));
                JSONArray cache=new JSONArray();for(JSONObject a:assets)cache.put(a);try{write(new File(getFilesDir(),"gallery.json"),cache.toString());}catch(Exception ignored){}
                showGrid();loading=false;
            });
        }catch(Exception e){runOnUiThread(()->{connection.setText("○ PC indisponível");capacity.setText("Liga o PC para abrir originais ou adicionar fotos.");spaceBar.setProgress(0);loading=false;});}});
    }
    void showGrid(){
        if(grid==null)return;ArrayList<JSONObject> shown=new ArrayList<>();for(JSONObject a:assets)if((a.optString("name")+" "+a.optString("album")).toLowerCase(Locale.ROOT).contains(filter))shown.add(a);
        heading.setText(assets.isEmpty()?"As tuas memórias começam aqui":shown.size()+" fotografias e vídeos");more.setVisibility(nextOffset>=0?View.VISIBLE:View.GONE);
        grid.setAdapter(new BaseAdapter(){public int getCount(){return shown.size();}public Object getItem(int p){return shown.get(p);}public long getItemId(int p){return p;}
            public View getView(int p,View reused,android.view.ViewGroup parent){
                JSONObject asset=shown.get(p);LinearLayout cell=new LinearLayout(MainActivity.this);cell.setOrientation(1);cell.setBackground(rounded(0xffe6e9e0));cell.setClipToOutline(true);
                FrameLayout photo=new FrameLayout(MainActivity.this);int height=dp(116);cell.addView(photo,new LinearLayout.LayoutParams(-1,height));
                TextView placeholder=label(asset.optString("mime").startsWith("video/")?"▶":"▧",30,MUTED);placeholder.setGravity(Gravity.CENTER);photo.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
                ImageView im=new ImageView(MainActivity.this);im.setScaleType(ImageView.ScaleType.CENTER_CROP);photo.addView(im,new FrameLayout.LayoutParams(-1,-1));
                TextView title=label(asset.optString("album").isEmpty()?asset.optString("name"):asset.optString("album"),11,INK);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(7),dp(5),dp(7),dp(6));cell.addView(title);
                if(asset.optInt("thumb")==1)loadThumb(asset.optString("id"),im);
                cell.setOnClickListener(v->{Intent intent=new Intent(MainActivity.this,ViewerActivity.class);intent.putExtra("asset",asset.toString());startActivity(intent);});
                cell.setOnLongClickListener(v->{album(asset);return true;});return cell;
            }});
    }
    void loadThumb(String id,ImageView view){
        images.execute(()->{try{File folder=new File(getCacheDir(),"thumbs");folder.mkdirs();File f=new File(folder,id+".jpg");
            if(!f.exists()){trim(folder);net.download("/assets/"+id+"/thumb",f,null);}Bitmap b=BitmapFactory.decodeFile(f.toString());runOnUiThread(()->view.setImageBitmap(b));
        }catch(Exception ignored){}});
    }
    static synchronized void trim(File folder){File[] files=folder.listFiles();if(files==null)return;long bytes=0;for(File f:files)bytes+=f.length();if(bytes<48*1024*1024)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File f:files){bytes-=f.length();f.delete();if(bytes<32*1024*1024)break;}}
    void album(JSONObject asset){
        EditText input=new EditText(this);input.setSingleLine();input.setHint("Ex.: Família, Férias, Aniversários");input.setText(asset.optString("album"));
        new AlertDialog.Builder(this).setTitle("Álbum da fotografia").setView(input).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w)->work.execute(()->{
            try{net.json("/assets/"+asset.getString("id")+"/album",new JSONObject().put("album",input.getText().toString()));runOnUiThread(()->refresh(false));}catch(Exception e){runOnUiThread(()->message("Não foi possível guardar",explain(e)));}
        })).show();
    }
}
