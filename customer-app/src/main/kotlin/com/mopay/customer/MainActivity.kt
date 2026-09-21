package com.mopay.customer

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val bg = Color.rgb(246,247,249)
    private val black = Color.rgb(15,16,18)
    private val white = Color.WHITE
    private val accent = Color.parseColor(BuildConfig.ACCENT_HEX)
    private val green = Color.rgb(25,155,95)
    private val red = Color.rgb(211,63,63)
    private val gray = Color.rgb(100,105,112)
    private val customerId = "11111111-1111-1111-1111-111111111111"
    private var snapshot = JSONObject()
    private var cardStatus = "active"

    override fun onCreate(state: Bundle?) { super.onCreate(state); window.statusBarColor=black; load() }
    override fun onDestroy(){ executor.shutdownNow(); super.onDestroy() }

    private fun load(){ executor.execute { try { val d=SupabaseClient.rpc("get_customer_dashboard",JSONObject().put("p_customer_id",customerId)); runOnUiThread{snapshot=d; cardStatus=d.optJSONObject("card")?.optString("status","active")?:"active"; home()} } catch(e:Exception){runOnUiThread{home(error=e.message)}} } }

    private fun home(error:String?=null){
        val wallet=snapshot.optJSONObject("wallet")?:JSONObject(); val customer=snapshot.optJSONObject("customer")?:JSONObject(); val card=snapshot.optJSONObject("card")?:JSONObject()
        val root=screen(); val c=content(root)
        c.addView(appHeader(customer.optString("full_name","NGOH ERAN"),"Customer Wallet"))
        if(error!=null) c.addView(info("Offline / connection unavailable",error,red))
        val hero=panel(accent,24); hero.addView(row(text("AVAILABLE BALANCE",13,black,1),pill(BuildConfig.BRAND,black,accent))); hero.addView(text(money(wallet.optLong("balance"))+" FCFA",36,black,Typeface.BOLD)); hero.addView(text("Secure wallet • "+wallet.optString("currency","XAF"),12,black)); c.addView(hero,lp(-1,-2,0,16,0,0))
        c.addView(text("Quick actions",19,black,Typeface.BOLD),lp(-1,-2,0,20,0,8))
        val grid=LinearLayout(this); grid.orientation=LinearLayout.VERTICAL
        grid.addView(actionRow(listOf(Triple("↥","Top Up",{topUp()}),Triple("→","Send",{sendHub()}),Triple("▣","Pay",{payPage()}),Triple("↓","Receive",{receivePage()}))))
        c.addView(grid)
        val card=brandCard(card.optString("masked_number","•••• 4821"),customer.optString("full_name","NGOH ERAN"),cardStatus); c.addView(card,lp(-1,170,0,16,0,0)); c.addView(button("MANAGE CARD  ›",white,black){cardPage()},lp(-1,52,0,8,0,0))
        c.addView(sectionTitle("Recent activity","View all ›"){history()})
        val tx=snapshot.optJSONArray("transfers")?:JSONArray(); for(i in 0 until minOf(4,tx.length())) c.addView(txRow(tx.getJSONObject(i)))
        c.addView(nav())
        setContentView(root)
    }

    private fun sendHub(){ page("Send Money","Choose how you want to move money") { val c=it; c.addView(channelCard("C2C","Customer → Customer","Send to another MoPay customer", "→"){transferPage("C2C","customer","55555555-5555-5555-5555-555555555555","MO PAY CUSTOMER 02")}); c.addView(channelCard("C2M","Customer → Merchant","Pay a business directly", "▣"){transferPage("C2M","merchant","22222222-2222-2222-2222-222222222222","MoPay Demo Merchant")}); c.addView(channelCard("C2A","Customer → Agent","Send to a MoPay agent", "◉"){transferPage("C2A","agent","44444444-4444-4444-4444-444444444444","MoPay Agent 01")}) } }

    private fun payPage(){ page("Pay with MoPay","Merchant payment options") { val c=it; c.addView(channelCard("NFC PAY","Tap your physical card","For Android merchant terminals", ")))"){Toast.makeText(this,"Use the physical NFC card at the merchant terminal.",Toast.LENGTH_LONG).show()}); c.addView(channelCard("C2M","Pay a merchant account","Enter the merchant details and authorize", "▣"){transferPage("C2M","merchant","22222222-2222-2222-2222-222222222222","MoPay Demo Merchant")}) } }

    private fun transferPage(channel:String, receiverKind:String, receiverId:String, receiverName:String){ page(channel,"Secure transfer • XAF") { c->
        c.addView(recipientCard(receiverName,receiverKind.uppercase(),receiverId.take(8)+"…")); c.addView(text("Amount",15,black,Typeface.BOLD),lp(-1,-2,0,18,0,6)); val amount=moneyInput(); c.addView(amount,lp(-1,64,0,6,0,0)); c.addView(quickAmounts(amount)); c.addView(text("4-digit PIN",15,black,Typeface.BOLD),lp(-1,-2,0,18,0,6)); val pin=pinInput(); c.addView(pin,lp(-1,62,0,6,0,0)); c.addView(button("CONFIRM "+channel+"  →",accent,black){ val a=amount.text.toString().toLongOrNull(); val p=pin.text.toString(); if(a==null||a<=0){amount.error="Enter amount";return@button}; if(p.length!=4){pin.error="4 digits required";return@button}; doTransfer(channel,receiverKind,receiverId,a,p) },lp(-1,56,0,18,0,0)); c.addView(info("Transaction security","The transfer is authorized by your wallet PIN and recorded with a unique reference.",gray))
    } }

    private fun doTransfer(channel:String, receiverKind:String, receiverId:String, amount:Long,pin:String){ executor.execute{ try{ val b=JSONObject().put("p_channel",channel).put("p_sender_kind","customer").put("p_sender_id",customerId).put("p_receiver_kind",receiverKind).put("p_receiver_id",receiverId).put("p_amount",amount).put("p_pin",pin).put("p_note","Mobile transfer"); val r=SupabaseClient.rpc("transfer_money",b); runOnUiThread{if(r.optBoolean("success")){ successPage("Transfer completed",r.optString("reference"),amount,r.optLong("sender_balance_after")) }else fail(r.optString("reason","TRANSFER_DECLINED"))} }catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}} } }

    private fun topUp(){ page("Top Up Wallet","Add funds to your MoPay balance") { c-> val amount=moneyInput(); c.addView(text("Amount",15,black,Typeface.BOLD)); c.addView(amount,lp(-1,64,0,8,0,0)); c.addView(quickAmounts(amount)); c.addView(button("TOP UP WALLET  →",accent,black){val a=amount.text.toString().toLongOrNull();if(a==null||a<=0){amount.error="Enter amount";return@button};executor.execute{try{val r=SupabaseClient.rpc("customer_top_up",JSONObject().put("p_customer_id",customerId).put("p_amount",a).put("p_note","Wallet top up"));runOnUiThread{if(r.optBoolean("success"))successPage("Wallet funded",r.optString("reference"),a,r.optLong("balance_after")) else fail(r.optString("reason"))}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}},lp(-1,56,0,18,0,0)); c.addView(info("Operator integration","This wallet top-up is ledger-ready. MTN/Orange funding rails are the remaining API connection.",gray)) } }

    private fun cardPage(){ page("My MoPay Card","Control your physical NFC card") { c-> val card=snapshot.optJSONObject("card")?:JSONObject(); val customer=snapshot.optJSONObject("customer")?:JSONObject(); c.addView(brandCard(card.optString("masked_number","•••• 4821"),customer.optString("full_name","NGOH ERAN"),cardStatus)); c.addView(button(if(cardStatus=="active")"FREEZE CARD" else "UNFREEZE CARD",if(cardStatus=="active") red else accent,if(cardStatus=="active") white else black){changeCardStatus(if(cardStatus=="active")"frozen" else "active")},lp(-1,56,0,14,0,0)); c.addView(channelCard("PIN","Change card PIN","Update your secure 4-digit card PIN","•••"){changePin()}); c.addView(channelCard("NFC","Tap-to-pay","Use this card on supported MoPay NFC merchant terminals",")))"){Toast.makeText(this,"NFC card is ready for supported merchant terminals.",Toast.LENGTH_LONG).show()}) } }

    private fun changeCardStatus(status:String){executor.execute{try{val r=SupabaseClient.rpc("set_demo_card_status",JSONObject().put("p_card_token",snapshot.optJSONObject("card")?.optString("card_token","CARD_DEMO_4821")).put("p_status",status));runOnUiThread{if(r.optBoolean("success")){cardStatus=status;load()}else fail(r.optString("reason"))}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}}
    private fun changePin(){ page("Change PIN","Keep your card protected") { c-> val old=pinInput();val nw=pinInput();c.addView(label("Current PIN"));c.addView(old,lp(-1,62,0,6,0,14));c.addView(label("New PIN"));c.addView(nw,lp(-1,62,0,6,0,0));c.addView(button("UPDATE PIN",accent,black){if(old.text.length!=4||nw.text.length!=4){Toast.makeText(this,"Both PINs must contain 4 digits",Toast.LENGTH_SHORT).show();return@button};executor.execute{try{val r=SupabaseClient.rpc("change_demo_pin",JSONObject().put("p_card_token",snapshot.optJSONObject("card")?.optString("card_token","CARD_DEMO_4821")).put("p_old_pin",old.text.toString()).put("p_new_pin",nw.text.toString()));runOnUiThread{if(r.optBoolean("success"))successPage("PIN updated","SECURITY",0,snapshot.optJSONObject("wallet")?.optLong("balance")?:0) else fail(r.optString("reason"))}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}},lp(-1,56,0,18,0,0)) } }

    private fun history(){ page("Transaction history","Transfers, payments and wallet activity") { c-> val tx=snapshot.optJSONArray("transfers")?:JSONArray();if(tx.length()==0)c.addView(info("No transactions yet","Your completed activity will appear here.",gray));for(i in 0 until tx.length())c.addView(txRow(tx.getJSONObject(i))) } }
    private fun receivePage(){ page("Receive Money","Share your MoPay details") { c-> val me=snapshot.optJSONObject("customer")?:JSONObject(); c.addView(recipientCard(me.optString("full_name","NGOH ERAN"),"CUSTOMER",me.optString("phone_number","+237000000000"))); c.addView(info("How to receive","Give the sender your MoPay phone number. Incoming C2C, A2C and other supported transfers appear in your wallet automatically.",gray)); c.addView(button("COPY DETAILS",white,black){Toast.makeText(this,"Details ready to share",Toast.LENGTH_SHORT).show()},lp(-1,54,0,12,0,0)) } }
    private fun profile(){ page("Profile","Your MoPay identity") { c-> val me=snapshot.optJSONObject("customer")?:JSONObject();c.addView(profileCard(me.optString("full_name"),me.optString("phone_number")));c.addView(channelCard("ACCOUNT","Wallet status","Active and ready for supported transactions","✓"){});c.addView(channelCard("SECURITY","Security center","PIN and card controls","⌁"){cardPage()}) } }
    private fun settings(){ page("Settings","Application preferences") { c->c.addView(channelCard("NOTIFICATIONS","Transaction alerts","Control transaction notifications","◉"){Toast.makeText(this,"Notification preferences saved",Toast.LENGTH_SHORT).show()});c.addView(channelCard("LANGUAGE","English / Français","Language selection","Aa"){Toast.makeText(this,"Language selector opened",Toast.LENGTH_SHORT).show()});c.addView(channelCard("ABOUT","MoPay","Operator integration status and application information","i"){executor.execute{try{val r=SupabaseClient.rpc("operator_integration_status");runOnUiThread{AlertDialog.Builder(this).setTitle("Integration status").setMessage(r.toString(2)).setPositiveButton("OK",null).show()}}catch(e:Exception){runOnUiThread{fail(e.message?:"Connection error")}}}}) } }

    private fun more(){page("More","Account and security") {c->c.addView(channelCard("PROFILE","Personal information","View your account details","◉"){profile()});c.addView(channelCard("HISTORY","Transaction history","View every movement","▤"){history()});c.addView(channelCard("SETTINGS","Preferences","Security, notifications and language","⚙"){settings()});c.addView(channelCard("LOG OUT","Sign out","Close this session","↪"){Toast.makeText(this,"Session closed",Toast.LENGTH_SHORT).show()})}}

    private fun successPage(title:String,ref:String,amount:Long,balance:Long){page(title,"Completed securely") {c->val p=panel(black,26);p.addView(text("✓",52,accent,Typeface.BOLD).apply{gravity=Gravity.CENTER});p.addView(text(title,24,white,Typeface.BOLD).apply{gravity=Gravity.CENTER});if(amount>0)p.addView(text(money(amount)+" FCFA",28,white,Typeface.BOLD).apply{gravity=Gravity.CENTER;setPadding(0,8,0,0)});p.addView(text("Reference  "+ref,12,Color.LTGRAY).apply{gravity=Gravity.CENTER;setPadding(0,12,0,0)});p.addView(text("New balance  "+money(balance)+" FCFA",13,Color.LTGRAY).apply{gravity=Gravity.CENTER;setPadding(0,6,0,0)});c.addView(p,lp(-1,-2,0,8,0,12));c.addView(button("BACK TO HOME",accent,black){load()},lp(-1,56,0,8,0,0))}}
    private fun fail(reason:String){AlertDialog.Builder(this).setTitle("Transaction not completed").setMessage(reason.replace('_',' ')).setPositiveButton("OK",null).show()}

    private fun page(title:String,subtitle:String,body:(LinearLayout)->Unit){val root=screen();val c=content(root);val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};bar.addView(button("‹",Color.TRANSPARENT,black){home()},lp(48,48,0,0,8,0));bar.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text(title,22,black,Typeface.BOLD));addView(text(subtitle,11,gray))},lp(0,-2,1f,0,8,0));c.addView(bar);body(c);setContentView(root)}
    private fun screen():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
    private fun content(root:LinearLayout):LinearLayout{val s=ScrollView(this).apply{isFillViewport=true};val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(22))};s.addView(c);root.addView(s,LinearLayout.LayoutParams(-1,0,1f));return c}
    private fun appHeader(name:String,role:String)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(ImageView(this@MainActivity).apply{setImageResource(R.drawable.ic_mopay_logo)},lp(48,48));addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0);addView(text("MoPay • ${BuildConfig.BRAND}",20,black,Typeface.BOLD));addView(text("$role • $name",11,gray))},lp(0,-2,1f));addView(button("⋮",Color.TRANSPARENT,black){more()},lp(46,46))}
    private fun nav():View=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(0,dp(18),0,0);addView(navItem("⌂","Home"){home()},lp(0,68,1f));addView(navItem("→","Send"){sendHub()},lp(0,68,1f));addView(navItem("▣","Card"){cardPage()},lp(0,68,1f));addView(navItem("•••","More"){more()},lp(0,68,1f))}
    private fun navItem(i:String,l:String,a:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setOnClickListener{a()};addView(text(i,21,black,Typeface.BOLD));addView(text(l,10,gray,Typeface.BOLD))}
    private fun actionRow(items:List<Triple<String,String,()->Unit>>)=LinearLayout(this).apply{gravity=Gravity.CENTER;items.forEach{(i,l,a)->addView(action(i,l,a),lp(0,88,1f,4,4,0))}}
    private fun action(i:String,l:String,a:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=round(white,18);elevation=dp(2).toFloat();setOnClickListener{a()};addView(text(i,25,black,Typeface.BOLD));addView(text(l,11,gray,Typeface.BOLD).apply{setPadding(0,dp(6),0,0)})}
    private fun channelCard(code:String,title:String,sub:String,icon:String,a:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(white,20);elevation=dp(2).toFloat();setPadding(dp(16),dp(16),dp(16),dp(16));setOnClickListener{a()};addView(row(text(code,11,black,Typeface.BOLD),pill(icon,accent,black)));addView(text(title,18,black,Typeface.BOLD).apply{setPadding(0,dp(12),0,dp(4))});addView(text(sub,12,gray));layoutParams=lp(-1,-2,0,8,0,0)}
    private fun recipientCard(name:String,type:String,id:String)=panel(black,20).apply{addView(row(text(type,11,Color.LTGRAY,Typeface.BOLD),pill(BuildConfig.BRAND,accent,black)));addView(text(name,20,white,Typeface.BOLD).apply{setPadding(0,dp(14),0,dp(3))});addView(text(id,12,Color.LTGRAY))}
    private fun profileCard(name:String,phone:String)=panel(white,20).apply{addView(text(name,21,black,Typeface.BOLD));addView(text(phone,13,gray).apply{setPadding(0,dp(7),0,0)})}
    private fun brandCard(mask:String,name:String,status:String)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        val gd=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(accent,Color.rgb(20,20,22)));gd.cornerRadius=dp(22).toFloat();background=gd
        setPadding(dp(20),dp(17),dp(20),dp(17));elevation=dp(5).toFloat()
        addView(row(text(BuildConfig.BRAND,25,if(BuildConfig.BRAND=="MTN") black else white,Typeface.BOLD),text(")))" ,26,if(BuildConfig.BRAND=="MTN") black else white,Typeface.BOLD)))
        addView(text("MoMo",14,if(BuildConfig.BRAND=="MTN") black else white,Typeface.BOLD))
        addView(text(mask,25,white,Typeface.BOLD).apply{letterSpacing=.08f;setPadding(0,dp(15),0,dp(4))})
        addView(text(name,13,white,Typeface.BOLD))
        addView(row(text(if(status=="active")"● ACTIVE" else "● "+status.uppercase(),11,if(status=="active") Color.rgb(75,230,140) else Color.rgb(255,120,120),Typeface.BOLD),text("TAP  •  PAY  •  GO",11,Color.LTGRAY,Typeface.BOLD)))
    }
    private fun txRow(t:JSONObject)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(white,14);setPadding(dp(13),dp(11),dp(13),dp(11));layoutParams=lp(-1,68,0,5,0,0);val dir=t.optString("direction");addView(row(text(if(dir=="IN")"+ "+money(t.optLong("amount"))+" FCFA" else "- "+money(t.optLong("amount"))+" FCFA",13,if(dir=="IN") green else red,Typeface.BOLD),text(t.optString("channel","TRANSFER"),10,gray,Typeface.BOLD)));addView(text(t.optString("reference","—")+"  •  "+formatDate(t.optString("created_at")),10,gray))}
    private fun sectionTitle(l:String,r:String,a:()->Unit)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;layoutParams=lp(-1,-2,0,22,0,6);addView(text(l,18,black,Typeface.BOLD),lp(0,-2,1f));addView(button(r,Color.TRANSPARENT,gray){a()},lp(92,42))}
    private fun quickAmounts(e:EditText)=LinearLayout(this).apply{gravity=Gravity.CENTER;listOf(1000,2000,5000,10000).forEach{n->addView(button(NumberFormat.getNumberInstance(Locale.US).format(n),white,black){e.setText(n.toString())},lp(0,46,1f,3,3,0))};layoutParams=lp(-1,54)}
    private fun moneyInput()=EditText(this).apply{hint="0";textSize=28f;setTextColor(black);setHintTextColor(Color.LTGRAY);inputType=InputType.TYPE_CLASS_NUMBER;background=round(white,16);setPadding(dp(16),0,dp(16),0)}
    private fun pinInput()=EditText(this).apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD;textSize=23f;gravity=Gravity.CENTER;setTextColor(black);background=round(white,16)}
    private fun label(s:String)=text(s,14,black,Typeface.BOLD)
    private fun info(title:String,body:String,color:Int)=panel(white,16).apply{addView(text(title,13,color,Typeface.BOLD));addView(text(body,12,gray).apply{setPadding(0,dp(5),0,0)});layoutParams=lp(-1,-2,0,14,0,0)}
    private fun panel(color:Int,r:Int)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=round(color,r);setPadding(dp(17),dp(16),dp(17),dp(16))}
    private fun row(a:View,b:View)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(a,lp(0,-2,1f));addView(b,lp(-2,-2))}
    private fun pill(s:String,fill:Int,fg:Int)=text(s,10,fg,Typeface.BOLD).apply{gravity=Gravity.CENTER;background=round(fill,20);setPadding(dp(9),dp(5),dp(9),dp(5))}
    private fun button(s:String,fill:Int,fg:Int,a:()->Unit)=android.widget.Button(this).apply{text=s;textSize=12f;isAllCaps=false;typeface=Typeface.DEFAULT_BOLD;setTextColor(fg);background=round(fill,15);setOnClickListener{a()}}
    private fun money(n:Long)=NumberFormat.getNumberInstance(Locale.US).format(n)
    private fun formatDate(s:String)=try{val p=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US);SimpleDateFormat("dd MMM • HH:mm",Locale.US).format(p.parse(s)?:Date())}catch(_:Exception){s.take(16).replace('T',' ')}
    private fun round(c:Int,r:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun lp(w:Int,h:Int,weight:Float=0f,l:Int=0,t:Int=0,r:Int=0,b:Int=0)=LinearLayout.LayoutParams(w,h,weight).apply{setMargins(dp(l),dp(t),dp(r),dp(b))}
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
}
