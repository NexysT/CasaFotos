package pt.casafotos.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;

final class Net {
    final String base, pin, token;
    Net(Context context) {
        SharedPreferences p = context.getSharedPreferences("connection", 0);
        base = p.getString("base", ""); pin = p.getString("pin", ""); token = p.getString("token", "");
    }
    Net(String base, String pin, String token) { this.base=base; this.pin=pin; this.token=token; }
    boolean ready() { return !base.isEmpty() && pin.length()==64 && token.length()==64; }
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(); for (byte v:b) s.append(String.format("%02x",v&255)); return s.toString();
    }
    static String hash(InputStream in) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buf=new byte[1024*1024]; int n;
        while((n=in.read(buf))!=-1) digest.update(buf,0,n);
        return hex(digest.digest());
    }
    static String address(String text) throws Exception {
        text=text.trim(); if(!text.startsWith("https://")) text="https://"+text;
        URI u=new URI(text);
        if(!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null ||
            u.getQuery()!=null || u.getFragment()!=null || !(u.getPath().isEmpty()||u.getPath().equals("/")))
            throw new IOException("Indica o endereço mostrado no painel do PC.");
        return new URI("https",null,u.getHost(),u.getPort()<0?47831:u.getPort(),null,null,null).toString();
    }
    private static SSLSocketFactory factory(String pin, String[] captured) throws Exception {
        SSLContext ssl=SSLContext.getInstance("TLS");
        X509TrustManager manager=new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}
            public void checkClientTrusted(java.security.cert.X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain,String auth)throws CertificateException {
                try {
                    if(chain.length==0) throw new CertificateException("Sem certificado");
                    chain[0].checkValidity();
                    String actual=hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                    if(captured!=null) captured[0]=actual;
                    else if(pin.length()!=64 || !MessageDigest.isEqual(actual.getBytes("UTF-8"),pin.getBytes("UTF-8")))
                        throw new CertificateException("A identidade do PC mudou. Liga novamente pelo painel.");
                } catch(Exception e) { throw new CertificateException(e); }
            }
        };
        ssl.init(null,new TrustManager[]{manager},new SecureRandom()); return ssl.getSocketFactory();
    }
    // Descoberta sem credenciais: exige comparação no ecrã antes de emparelhar.
    static String probe(String base) throws Exception {
        String[] captured=new String[1];
        HttpsURLConnection c=(HttpsURLConnection)new URL(base+"/hello").openConnection();
        c.setSSLSocketFactory(factory("",captured)); c.setHostnameVerifier((h,s)->true);
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(8000);
        try {
            JSONObject hello=readJson(c); if(!"CasaFotos".equals(hello.optString("app"))) throw new IOException("Servidor desconhecido.");
            return captured[0];
        } finally {c.disconnect();}
    }
    HttpsURLConnection open(String path,String method) throws Exception {
        if(pin.length()!=64) throw new IOException("Liga a aplicação ao PC.");
        HttpsURLConnection c=(HttpsURLConnection)new URL(base+path).openConnection();
        c.setSSLSocketFactory(factory(pin,null)); c.setHostnameVerifier((h,s)->true);
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(120000);
        c.setRequestMethod(method); if(!token.isEmpty()) c.setRequestProperty("Authorization","Bearer "+token);
        return c;
    }
    static JSONObject readJson(HttpsURLConnection c)throws Exception {
        int code=c.getResponseCode(); InputStream in=code<400?c.getInputStream():c.getErrorStream();
        if(in==null) throw new IOException("O PC não respondeu ("+code+").");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(in) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>4*1024*1024)throw new IOException("Resposta demasiado grande.");}}
        JSONObject json=new JSONObject(out.toString("UTF-8"));
        if(code>=400) throw new IOException(json.optString("error","Erro "+code));
        return json;
    }
    JSONObject json(String path,JSONObject data)throws Exception {
        HttpsURLConnection c=open(path,data==null?"GET":"POST");
        try {
            if(data!=null) {byte[] bytes=data.toString().getBytes("UTF-8");c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            return readJson(c);
        } finally {c.disconnect();}
    }
    interface Progress { void update(long value); }
    JSONObject upload(InputStream source,long size,String hash,String name,String mime,Progress progress)throws Exception {
        HttpsURLConnection c=open("/upload","POST");
        try(source) {
            c.setDoOutput(true);c.setFixedLengthStreamingMode(size);
            c.setRequestProperty("X-SHA256",hash);c.setRequestProperty("X-Filename",URLEncoder.encode(name,"UTF-8").replace("+","%20"));c.setRequestProperty("Content-Type",mime);
            try(OutputStream out=c.getOutputStream()) {
                byte[] buffer=new byte[256*1024];int n;long total=0;
                while((n=source.read(buffer))!=-1){out.write(buffer,0,n);total+=n;progress.update(total);}
                if(total!=size)throw new IOException("O ficheiro mudou. Tenta novamente.");
            }
            return readJson(c);
        } finally {c.disconnect();}
    }
    void download(String path,File target,String expected)throws Exception {
        HttpsURLConnection c=open(path,"GET");File part=new File(target+".part");
        try {
            if(c.getResponseCode()!=200)throw new IOException("Não foi possível abrir o ficheiro.");
            long length=c.getContentLengthLong();
            if(length>target.getParentFile().getUsableSpace()-64*1024*1024)throw new IOException("Falta espaço no telemóvel para abrir este original.");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)) {
                byte[] b=new byte[256*1024];int n;
                while((n=in.read(b))!=-1){out.write(b,0,n);digest.update(b,0,n);total+=n;}
                out.getFD().sync();
            }
            if(length>=0 && total!=length)throw new IOException("Transferência incompleta.");
            if(expected!=null && !expected.equals(hex(digest.digest())))throw new IOException("O original não passou na verificação.");
            if(!part.renameTo(target))throw new IOException("Não foi possível guardar a pré-visualização.");
        } finally {c.disconnect();part.delete();}
    }
}
