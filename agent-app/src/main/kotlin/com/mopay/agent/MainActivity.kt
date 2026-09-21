package com.mopay.agent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
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
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity:Activity(){
 private val ex=Executors.newSingleThreadExecutor();private val bg=Color.rgb(246,247,249);private val black=Color.rgb(15,16,18);private val white=Color.WHITE;private val accent=Color.parseColor(BuildConfig.ACCENT_HEX);private val green=Color.rgb(25,155,95);private val red=Color.rgb(211,63,63);private val gray=Color.rgb(100,105,112);private val agentId="44444444-4444-4444-4444-444444444444";private var dash=JSONObject()
 override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=black;load()};override fun onDestroy(){ex.shutdownNow();super.onDestroy()}
 private fun load(){ex.execute{try{val d=SupabaseClient.rpc("get_agent_dashboard",JSONObject().put("p_agent_id",agentId));runOnUiThread{dash=d;home()}}catch(e:Exception){runOnUiThread{home(e.message)}}}}
 private fun home(err:String?=null){val root=screen();val c=content(root);val a=dash.optJSONObject("agent")?:JSONObject();val w=dash.optJSONObject("wallet")?:JSONObject();c.addView(header(a.optString("full_name","MoPay Agent 01"),"Agent Wallet"));if(err!=null)c.addView(info("Connection issue",err,red));val hero=panel(accent,24);hero.addView(text("AVAILABLE BALANCE",12,black,Typeface.BOLD));hero.addView(text(money(w.optLong("balance"))+" FCFA",34,black,Typeface.BOLD).apply{setPadding(0,6,0,0)});hero.addView(text("Agent account • ${BuildConfig.BRAND}",12,black));c.addView(hero,lp(-1,-2,0,16,0,0));c.addView(text("Agent operations",18,black,Typeface.BOLD),lp(-1,-2,0,20,0,8));c.addView(channel("A2C","Agent → Customer","Send cash from your agent wallet to a customer","→"){transfer("A2C","customer","11111111-1111-1111-1111-111111111111","NGOH ERAN")});c.addView(channel("C2A","Customer → Agent","Receive money from a customer","↓"){receivePage()});c.addView(channel("HISTORY","Agent ledger","Review all incoming and outgoing transfers","▤"){history()});c.addView(channel("SETTINGS","Agent settings","Security and integration settings","⚙"){settings()});c.addView(nav());setContentView(root)}
 private fun transfer(ch:String,kind:String,id:String,name:String){page(ch,"Secure agent transfer"){c->c.addView(recipient(name,kind,id.take(8)+"…"));val amt=moneyInput();c.addView(text("Amount",14,black,Typeface.BOLD),lp(-1,-2,0,18,0,6));c.addView(amt,lp(-1,64));c.addView(button("CONFIRM "+ch+"  →",accent,black){val n=amt.text.toString().toLongOrNull();if(n==null||n<=0){amt.error="Enter amount";return@button};doTransfer(ch,kind,id,n)},lp(-1,56,0,18))}}
 private fun receivePage(){page("C2A • Receive from customer","Customer sends money to this agent"){c->c.addView(recipient("MoPay Agent 01","AGENT","+237000000002"));c.addView(info("How it works","The customer initiates C2A using the Customer app. Once the operator API is connected, the same reference can be settled externally.",gray));c.addView(button("COPY AGENT DETAILS",white,black){Toast.makeText(this,"Agent details ready",Toast.LENGTH_SHORT).show()},lp(-1,54,0,12))}}
 private fun doTransfer(ch:String,kind:String,id:String,a:Long){ex.execute{try{val r=SupabaseClient.rpc("transfer_money",JSONObject().put("p_channel",ch).put("p_sender_kind","agent").put("p_sender_id",agentId).put("p_receiver_kind",kind).put("p_receiver_id",id).put("p_amount",a).put("p_note","Agent transfer"));runOnUiThread{if(r.optBoolean("success"))success(r)else fail(r.optString("reason"))}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}}
 private fun history(){page("Agent history","Incoming and outgoing ledger activity"){c->val tx=dash.optJSONArray("transfers")?:JSONArray();if(tx.length()==0)c.addView(info("No transfers","Activity will appear here.",gray));for(i in 0 until tx.length())c.addView(txRow(tx.getJSONObject(i)))}}
 private fun settings(){page("Settings","Agent security and integration"){c->c.addView(info("Agent account","MoPay Agent 01 • +237000000002",gray));c.addView(channel("Security","Operator authorization and audit controls","Review security and audit controls","⌁"){});c.addView(channel("Integration","MTN/Orange API connection status","Check MTN/Orange API connection status","↔"){ex.execute{try{val r=SupabaseClient.rpc("operator_integration_status");runOnUiThread{AlertDialog.Builder(this).setTitle("Integration status").setMessage(r.toString(2)).setPositiveButton("OK",null).show()}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}})}}
 private fun page(t:String,s:String,body:(LinearLayout)->Unit){val r=screen();val c=content(r);val b=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};b.addView(button("‹",Color.TRANSPARENT,black){load()},lp(48,48,0,0,8));b.addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;addView(text(t,22,black,Typeface.BOLD));addView(text(s,11,gray))},lp(0,-2,1f));c.addView(b);body(c);setContentView(r)}
 private fun success(r:JSONObject){page("Transfer completed","Agent transaction"){c->val p=panel(black,24);p.addView(text("✓",52,accent,Typeface.BOLD).apply{gravity=Gravity.CENTER});p.addView(text(money(r.optLong("amount"))+" FCFA",30,white,Typeface.BOLD).apply{gravity=Gravity.CENTER});p.addView(text("Reference  "+r.optString("reference"),12,Color.LTGRAY).apply{gravity=Gravity.CENTER;setPadding(0,10,0,0)});c.addView(p,lp(-1,-2,0,8,0,12));c.addView(button("DONE",accent,black){load()},lp(-1,56))}}
 private fun fail(s:String){AlertDialog.Builder(this).setTitle("Transfer declined").setMessage(s.replace('_',' ')).setPositiveButton("OK",null).show()}
 private fun header(name:String,role:String)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(ImageView(this@MainActivity).apply{setImageResource(R.drawable.ic_mopay_logo)},lp(48,48));addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0);addView(text("MoPay • ${BuildConfig.BRAND}",20,black,Typeface.BOLD));addView(text("$role • $name",11,gray))},lp(0,-2,1f));addView(button("⋮",Color.TRANSPARENT,black){settings()},lp(46,46))}
 private fun nav():View=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(0,dp(18),0,0);addView(navItem("⌂","Home"){load()},lp(0,68,1f));addView(navItem("→","A2C"){transfer("A2C","customer","11111111-1111-1111-1111-111111111111","NGOH ERAN")},lp(0,68,1f));addView(navItem("↓","C2A"){receivePage()},lp(0,68,1f));addView(navItem("▤","History"){history()},lp(0,68,1f))}
 private fun channel(code:String,title:String,sub:String,icon:String,a:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(white,18);setPadding(dp(16),dp(16),dp(16),dp(16));setOnClickListener{a()};addView(row(text(code,11,black,Typeface.BOLD),pill(icon,accent,black)));addView(text(title,17,black,Typeface.BOLD).apply{setPadding(0,dp(11),0,dp(4))});addView(text(sub,12,gray));layoutParams=lp(-1,-2,0,8)}
 private fun recipient(n:String,t:String,id:String)=panel(black,20).apply{addView(row(text(t,11,Color.LTGRAY,Typeface.BOLD),pill(BuildConfig.BRAND,accent,black)));addView(text(n,20,white,Typeface.BOLD).apply{setPadding(0,12,0,4)});addView(text(id,12,Color.LTGRAY))}
 private fun txRow(t:JSONObject)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(white,14);setPadding(dp(13),dp(11),dp(13),dp(11));layoutParams=lp(-1,66,0,5);val ok=t.optString("status")=="successful";addView(row(text((if(t.optString("direction")=="IN")"+ " else "- ")+money(t.optLong("amount"))+" FCFA",13,if(ok) green else red,Typeface.BOLD),pill(t.optString("channel","TRANSFER"),if(ok) Color.rgb(230,248,238) else Color.rgb(255,235,235),if(ok) green else red)));addView(text(t.optString("reference","—"),10,gray))}
 private fun info(t:String,b:String,c:Int)=panel(white,16).apply{addView(text(t,13,c,Typeface.BOLD));addView(text(b,12,gray).apply{setPadding(0,5,0,0)});layoutParams=lp(-1,-2,0,12)}
 private fun screen()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
 private fun content(r:LinearLayout):LinearLayout{val s=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(22))};s.addView(c);r.addView(s,LinearLayout.LayoutParams(-1,0,1f));return c}
 private fun panel(c:Int,r:Int)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(c,r);setPadding(dp(17),dp(16),dp(17),dp(16))}
 private fun row(a:View,b:View)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(a,lp(0,-2,1f));addView(b,lp(-2,-2))}
 private fun pill(s:String,f:Int,c:Int)=text(s,10,c,Typeface.BOLD).apply{gravity=Gravity.CENTER;background=round(f,20);setPadding(dp(9),dp(5),dp(9),dp(5))}
 private fun moneyInput()=EditText(this).apply{hint="0";textSize=28f;inputType=InputType.TYPE_CLASS_NUMBER;setTextColor(black);background=round(white,16);setPadding(dp(16),0,dp(16),0)}
 private fun button(s:String,f:Int,c:Int,a:()->Unit)=android.widget.Button(this).apply{text=s;textSize=12f;isAllCaps=false;typeface=Typeface.DEFAULT_BOLD;setTextColor(c);background=round(f,15);setOnClickListener{a()}}
 private fun navItem(i:String,l:String,a:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setOnClickListener{a()};addView(text(i,21,black,Typeface.BOLD));addView(text(l,10,gray,Typeface.BOLD))}
 private fun text(v:String,s:Number,c:Int,st:Int=Typeface.NORMAL)=TextView(this).apply{text=v;textSize=s.toFloat();setTextColor(c);typeface=Typeface.create("sans",st)}
 private fun round(c:Int,r:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
 private fun lp(w:Int,h:Int,weight:Number=0,l:Int=0,t:Int=0,r:Int=0,b:Int=0)=LinearLayout.LayoutParams(w,h,weight.toFloat()).apply{setMargins(dp(l),dp(t),dp(r),dp(b))}
 private fun money(v:Long)=NumberFormat.getNumberInstance(Locale.US).format(v)
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
