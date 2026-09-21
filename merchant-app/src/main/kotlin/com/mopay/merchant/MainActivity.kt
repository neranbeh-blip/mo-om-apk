package com.mopay.merchant

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor=Executors.newSingleThreadExecutor(); private val bg=Color.rgb(246,247,249);private val black=Color.rgb(15,16,18);private val white=Color.WHITE;private val accent=Color.parseColor(BuildConfig.ACCENT_HEX);private val green=Color.rgb(25,155,95);private val red=Color.rgb(211,63,63);private val gray=Color.rgb(100,105,112)
    private val merchantId="22222222-2222-2222-2222-222222222222"; private var dash=JSONObject();private var nfc:NfcAdapter?=null;private var cardToken="";private var tech="";private var amount=0L
    override fun onCreate(s:Bundle?){super.onCreate(s);window.statusBarColor=black;nfc=NfcAdapter.getDefaultAdapter(this);load()}
    override fun onResume(){super.onResume();try{nfc?.enableReaderMode(this,{tag->runOnUiThread{handleTag(tag)}},NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null)}catch(_:Exception){}}
    override fun onPause(){try{nfc?.disableReaderMode(this)}catch(_:Exception){};super.onPause()}
    override fun onDestroy(){executor.shutdownNow();super.onDestroy()}
    private fun load(){executor.execute{try{val d=SupabaseClient.rpc("get_merchant_dashboard_v2",JSONObject().put("p_merchant_id",merchantId));runOnUiThread{dash=d;home()}}catch(e:Exception){runOnUiThread{home(e.message)}}}}
    private fun home(error:String?=null){val root=screen();val c=content(root);val m=dash.optJSONObject("merchant")?:JSONObject();val w=dash.optJSONObject("wallet")?:JSONObject();val t=dash.optJSONObject("today")?:JSONObject();c.addView(header(m.optString("business_name","MoPay Merchant"),"Merchant Terminal"));if(error!=null)c.addView(info("Connection issue",error,red));val hero=panel(black,24);hero.addView(row(text("${BuildConfig.BRAND} MERCHANT",13,Color.LTGRAY,Typeface.BOLD),pill("NFC READY",accent,black)));hero.addView(text(if(cardToken.isBlank())"Ready for contactless payment" else "CARD DETECTED",22,white,Typeface.BOLD).apply{setPadding(0,dp(15),0,dp(5))});hero.addView(text(if(cardToken.isBlank())"Tap a MoPay NFC card or supported Android HCE phone" else "${tech.uppercase()} • card ready",12,Color.LTGRAY));hero.addView(text("◉",52,accent,Typeface.BOLD).apply{gravity=Gravity.CENTER;setPadding(0,10,0,4)});c.addView(hero,lp(-1,-2,0,16,0,0));c.addView(text("Payment amount",16,black,Typeface.BOLD),lp(-1,-2,0,18,0,7));val amt=moneyInput();c.addView(amt,lp(-1,64));c.addView(quick(amt));c.addView(button("PROCEED TO NFC PAYMENT  →",accent,black){val a=amt.text.toString().toLongOrNull();if(a==null||a<=0){amt.error="Enter amount";return@button};amount=a;if(cardToken.isBlank()){Toast.makeText(this,"Tap the customer's NFC card first",Toast.LENGTH_LONG).show()}else authorize(a)},lp(-1,56,0,10,0,0));val stats=panel(white,18);stats.addView(row(stat("TRANSACTIONS",t.optInt("transaction_count").toString()),stat("SUCCESS",t.optInt("successful_count").toString())));stats.addView(row(stat("FAILED",t.optInt("failed_count").toString()),stat("TOTAL",money(t.optLong("total_amount"))+" XAF")));c.addView(text("Today",18,black,Typeface.BOLD),lp(-1,-2,0,22,0,7));c.addView(stats);c.addView(section("Recent transactions","View all ›"){transactions()});val tx=dash.optJSONArray("transfers")?:JSONArray();for(i in 0 until minOf(5,tx.length()))c.addView(txRow(tx.getJSONObject(i)));c.addView(nav());setContentView(root)}
    private fun authorize(a:Long){val pin=EditText(this).apply{hint="4-digit PIN";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD;textSize=23f;gravity=Gravity.CENTER};AlertDialog.Builder(this).setTitle("Authorize payment").setMessage("Charge ${money(a)} FCFA\nCard: $cardToken\nNFC: $tech").setView(pin).setNegativeButton("CANCEL",null).setPositiveButton("PAY",null).create().also{d->d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(pin.text.length!=4){pin.error="4 digits";return@setOnClickListener};d.dismiss();process(a,pin.text.toString())}};d.show()}}
    private fun process(a:Long,pin:String){executor.execute{try{val r=SupabaseClient.rpc("process_nfc_c2m",JSONObject().put("p_card_token",cardToken).put("p_merchant_id",merchantId).put("p_amount",a).put("p_pin",pin));runOnUiThread{if(r.optBoolean("success")){success(r)}else fail(r.optString("reason","PAYMENT_DECLINED"))}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}}
    private fun handleTag(tag:Tag){val techs=tag.techList.map{it.substringAfterLast('.')}.distinct();val n=readNdef(tag);cardToken=if(!n.isNullOrBlank()&&n.startsWith("CARD_"))n else "CARD_DEMO_4821";tech=if(techs.contains("IsoDep"))"ISO-DEP / CONTACTLESS" else techs.joinToString(" / ").ifBlank{"NFC"};Toast.makeText(this,"NFC detected • $tech",Toast.LENGTH_SHORT).show();home()}
    private fun readNdef(tag:Tag):String? {
        return try {
            val n = Ndef.get(tag) ?: return null
            n.connect()
            val m: NdefMessage? = n.ndefMessage
            val p = m?.records?.firstOrNull()?.payload
            n.close()
            p?.let { decode(it) }
        } catch (_: Exception) {
            null
        }
    }
    private fun decode(p:ByteArray):String{if(p.isEmpty())return "";val l=p[0].toInt() and 0x3f;val s=1+l;return if(s<p.size)String(p,s,p.size-s,Charset.forName("UTF-8")) else String(p,Charset.forName("UTF-8"))}
    private fun m2m(){page("M2M • Merchant transfer","Move funds to another merchant"){c->val a=moneyInput();c.addView(label("Amount"));c.addView(a,lp(-1,64,0,6,0,0));c.addView(quick(a));c.addView(button("SEND TO MERCHANT  →",accent,black){val n=a.text.toString().toLongOrNull();if(n==null||n<=0){a.error="Enter amount";return@button};doTransfer("M2M","merchant","66666666-6666-6666-6666-666666666666",n)},lp(-1,56,0,18,0,0));c.addView(info("Pilot destination","The second merchant account can be configured in Supabase before operator integration.",gray))}}
    private fun doTransfer(ch:String,kind:String,id:String,a:Long){executor.execute{try{val r=SupabaseClient.rpc("transfer_money",JSONObject().put("p_channel",ch).put("p_sender_kind","merchant").put("p_sender_id",merchantId).put("p_receiver_kind",kind).put("p_receiver_id",id).put("p_amount",a).put("p_note","Merchant transfer"));runOnUiThread{if(r.optBoolean("success"))success(r)else fail(r.optString("reason"))}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}}
    private fun transactions(){page("Transactions","Merchant payment and transfer history"){c->val tx=dash.optJSONArray("transfers")?:JSONArray();if(tx.length()==0)c.addView(info("No transactions","Completed and declined activity will appear here.",gray));for(i in 0 until tx.length())c.addView(txRow(tx.getJSONObject(i)))}}
    private fun reports(){page("Reports","Operational performance"){c->val t=dash.optJSONObject("today")?:JSONObject();c.addView(metric("Successful amount",money(t.optLong("total_amount"))+" FCFA",green));c.addView(metric("Transactions",t.optInt("transaction_count").toString(),black));c.addView(metric("Success rate",if(t.optInt("transaction_count")==0)"0%" else ((t.optDouble("successful_count")/t.optDouble("transaction_count"))*100).toInt().toString()+"%",accent));c.addView(info("Export","This screen is ready to connect to PDF/CSV reporting when the reporting service is enabled.",gray));c.addView(button("REFRESH REPORT",white,black){load()},lp(-1,54,0,10,0,0))}}
    private fun settings(){page("Settings","Terminal configuration"){c->c.addView(info("Terminal","NFC: "+if(nfc!=null)"Available" else "Not available",gray));c.addView(channel("NFC reader","Reader mode is active while this screen is open","◉"){});c.addView(channel("Security","PIN is requested before payment authorization","⌁"){});c.addView(channel("Operator integration","MTN/Orange API credentials will be connected at the integration layer","↔"){executor.execute{try{val r=SupabaseClient.rpc("operator_integration_status");runOnUiThread{AlertDialog.Builder(this).setTitle("Integration status").setMessage(r.toString(2)).setPositiveButton("OK",null).show()}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}})}}
    private fun nav():View=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(0,dp(18),0,0);addView(navItem("⌂","Home"){load()},lp(0,68,1f));addView(navItem("◉","Scan"){cardToken="";home()},lp(0,68,1f));addView(navItem("↔","M2M"){m2m()},lp(0,68,1f));addView(navItem("▤","Reports"){reports()},lp(0,68,1f));addView(navItem("⚙","Settings"){settings()},lp(0,68,1f))}
    private fun page(title:String,sub:String,body:(LinearLayout)->Unit){val root=screen();val c=content(root);val b=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};b.addView(button("‹",Color.TRANSPARENT,black){load()},lp(48,48,0,0,8,0));b.addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;addView(text(title,22,black,Typeface.BOLD));addView(text(sub,11,gray))},lp(0,-2,1f));c.addView(b);body(c);setContentView(root)}
    private fun success(r:JSONObject){page("Payment completed","Secure merchant transaction"){c->val p=panel(black,24);p.addView(text("✓",52,accent,Typeface.BOLD).apply{gravity=Gravity.CENTER});p.addView(text(money(r.optLong("amount"))+" FCFA",30,white,Typeface.BOLD).apply{gravity=Gravity.CENTER});p.addView(text("Reference  "+r.optString("reference"),12,Color.LTGRAY).apply{gravity=Gravity.CENTER;setPadding(0,10,0,0)});p.addView(text("Customer balance  "+money(r.optLong("sender_balance_after"))+" FCFA",12,Color.LTGRAY).apply{gravity=Gravity.CENTER;setPadding(0,5,0,0)});c.addView(p,lp(-1,-2,0,8,0,12));c.addView(button("DONE",accent,black){load()},lp(-1,56))}}
    private fun fail(s:String){AlertDialog.Builder(this).setTitle("Transaction declined").setMessage(s.replace('_',' ')).setPositiveButton("OK",null).show()}
    private fun header(name:String,role:String)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(ImageView(this@MainActivity).apply{setImageResource(R.drawable.ic_mopay_logo)},lp(48,48));addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0);addView(text("MoPay • ${BuildConfig.BRAND}",20,black,Typeface.BOLD));addView(text("$role • $name",11,gray))},lp(0,-2,1f));addView(button("⋮",Color.TRANSPARENT,black){settings()},lp(46,46))}
    private fun screen()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
    private fun content(r:LinearLayout):LinearLayout{val s=ScrollView(this).apply{isFillViewport=true};val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(22))};s.addView(c);r.addView(s,LinearLayout.LayoutParams(-1,0,1f));return c}
    private fun quick(e:EditText)=LinearLayout(this).apply{gravity=Gravity.CENTER;listOf(1000,2000,5000,10000).forEach{n->addView(button(NumberFormat.getNumberInstance(Locale.US).format(n),white,black){e.setText(n.toString())},lp(0,46,1f,3,3,0))}}
    private fun moneyInput()=EditText(this).apply{hint="0";textSize=28f;inputType=InputType.TYPE_CLASS_NUMBER;setTextColor(black);setHintTextColor(Color.LTGRAY);background=round(white,16);setPadding(dp(16),0,dp(16),0)}
    private fun label(s:String)=text(s,14,black,Typeface.BOLD)
    private fun channel(title:String,sub:String,icon:String,a:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(white,18);setPadding(dp(16),dp(16),dp(16),dp(16));setOnClickListener{a()};addView(row(text(title,16,black,Typeface.BOLD),pill(icon,accent,black)));addView(text(sub,12,gray).apply{setPadding(0,dp(8),0,0)});layoutParams=lp(-1,-2,0,8)}
    private fun metric(title:String,value:String,color:Int)=panel(white,18).apply{addView(text(title,11,gray,Typeface.BOLD));addView(text(value,25,color,Typeface.BOLD).apply{setPadding(0,dp(7),0,0)});layoutParams=lp(-1,-2,0,8)}
    private fun stat(l:String,v:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text(l,10,gray,Typeface.BOLD));addView(text(v,17,black,Typeface.BOLD).apply{setPadding(0,dp(5),0,0)})}
    private fun txRow(t:JSONObject)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(white,14);setPadding(dp(13),dp(11),dp(13),dp(11));layoutParams=lp(-1,68,0,5);val ok=t.optString("status")=="successful";addView(row(text((if(ok)"✓ " else "× ")+money(t.optLong("amount"))+" FCFA",13,if(ok) green else red,Typeface.BOLD),pill(t.optString("channel","PAY"),if(ok) Color.rgb(230,248,238) else Color.rgb(255,235,235),if(ok) green else red)));addView(text(t.optString("reference","—")+" • "+formatDate(t.optString("created_at")),10,gray))}
    private fun info(t:String,b:String,c:Int)=panel(white,16).apply{addView(text(t,13,c,Typeface.BOLD));addView(text(b,12,gray).apply{setPadding(0,dp(5),0,0)});layoutParams=lp(-1,-2,0,12)}
    private fun section(l:String,r:String,a:()->Unit)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;layoutParams=lp(-1,-2,0,22,0,6);addView(text(l,18,black,Typeface.BOLD),lp(0,-2,1f));addView(button(r,Color.TRANSPARENT,gray){a()},lp(95,42))}
    private fun panel(c:Int,r:Int)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(c,r);setPadding(dp(17),dp(16),dp(17),dp(16))}
    private fun row(a:View,b:View)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(a,lp(0,-2,1f));addView(b,lp(-2,-2))}
    private fun pill(s:String,fill:Int,fg:Int)=text(s,10,fg,Typeface.BOLD).apply{gravity=Gravity.CENTER;background=round(fill,20);setPadding(dp(9),dp(5),dp(9),dp(5))}
    private fun button(s:String,fill:Int,fg:Int,a:()->Unit)=android.widget.Button(this).apply{text=s;textSize=12f;isAllCaps=false;typeface=Typeface.DEFAULT_BOLD;setTextColor(fg);background=round(fill,15);setOnClickListener{a()}}
    private fun navItem(i:String,l:String,a:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setOnClickListener{a()};addView(text(i,21,black,Typeface.BOLD));addView(text(l,10,gray,Typeface.BOLD))}
    private fun text(v:String,s:Number,c:Int,st:Int=Typeface.NORMAL)=TextView(this).apply{text=v;textSize=s.toFloat();setTextColor(c);typeface=Typeface.create("sans",st)}
    private fun round(c:Int,r:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun money(v:Long)=NumberFormat.getNumberInstance(Locale.US).format(v)
    private fun formatDate(s:String)=try{val p=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US);SimpleDateFormat("dd MMM • HH:mm",Locale.US).format(p.parse(s)?:Date())}catch(_:Exception){s.take(16).replace('T',' ')}
    private fun lp(w:Int,h:Int,weight:Number=0f,l:Int=0,t:Int=0,r:Int=0,b:Int=0)=LinearLayout.LayoutParams(w,h,weight.toFloat()).apply{setMargins(dp(l),dp(t),dp(r),dp(b))}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
