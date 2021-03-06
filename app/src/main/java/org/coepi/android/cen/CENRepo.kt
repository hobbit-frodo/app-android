package org.coepi.android.cen

import android.os.Handler
import androidx.lifecycle.MutableLiveData
import org.coepi.android.system.log.log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.http.Path
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// CoEpiRepo coordinates local Storage (Room) and network API calls to manage the CoEpi protocol
//  (A) refreshCENAndCENKeys: CENKey generation every 7 days, CEN Generation every 15 minutes  (stored in Room)
//  (B) insertCEN: Storage of observed CENs from other BLE devices
class CENRepo(private val cenApi: CENApi, private val cenDao: CENDao, private val cenkeyDao: CENKeyDao, private val cenReportDao: CENReportDao)  {
    // ------------------------- CEN Management
    // the latest CENKey (AES in base64 encoded form), loaded from local storage
    private var cenKey: String = ""
    private var cenKeyTimestamp = 0
    
    // the latest CEN (ByteArray form), generated using cenKey
    var CEN : MutableLiveData<ByteArray> = MutableLiveData<ByteArray>()
    var CENKeyLifetimeInSeconds = 7*86400 // every 7 days a new key is generated
    var CENLifetimeInSeconds = 15*60   // every 15 mins a new CEN is generated
    private val periodicCENKeysCheckFrequencyInSeconds = 60*30 // run every 30 mins

    // last time (unix timestamp) the CENKeys were requested
    var lastCENKeysCheck = 0

    init {
        CEN.value = ByteArray(0)

        // load last CENKey + CENKeytimestamp from local storage
        val lastKeys = cenkeyDao.lastCENKeys(1)
        lastKeys?.let {
            if (lastKeys.isNotEmpty()) {
                val lk = lastKeys[0]
                lk.let {
                    cenKey = it.cenKey!!
                    cenKeyTimestamp = it.timeStamp
                }
            }
        }
        // Setup regular CENKey refresh + CEN refresh
        //  Production: refresh CEN every 15m, refresh CENKey every 7 days
        //  Testing: refresh CEN every 15s, refresh CENKey every minute
        CENKeyLifetimeInSeconds = 15
        CENLifetimeInSeconds = 60
        refreshCENAndCENKeys()

        // Setup regular CENKeysCheck
        periodicCENKeysCheck()
    }

    private fun refreshCENAndCENKeys() {
        val curTimestamp = (System.currentTimeMillis() / 1000L).toInt()
        if ( ( cenKeyTimestamp == 0 ) || ( roundedTimestamp(curTimestamp) > roundedTimestamp(cenKeyTimestamp) ) ) {
            // generate a new AES Key and store it in local storage
            val secretKey = KeyGenerator.getInstance("AES").generateKey()
            cenKey = android.util.Base64.encodeToString(secretKey.encoded,0)
            cenKeyTimestamp = curTimestamp
            cenkeyDao.insert(CENKey(cenKeyTimestamp, cenKey))
        }
        CEN.value = generateCEN(cenKey, curTimestamp)
        Handler().postDelayed({
            refreshCENAndCENKeys()
        }, CENLifetimeInSeconds * 1000L)
    }

    private fun generateCEN(CENKey : String, ts : Int)  : ByteArray {
        // decode the base64 encoded key
        val decodedCENKey = android.util.Base64.decode(CENKey,0)
        // rebuild secretKey using SecretKeySpec
        val secretKey: SecretKey = SecretKeySpec(decodedCENKey, 0, decodedCENKey.size, "AES")
        val cipher: Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(IntToByteArray(roundedTimestamp(ts)))
    }

    private fun roundedTimestamp(ts : Int) : Int {
        val epoch = ts / CENKeyLifetimeInSeconds
        return epoch * CENKeyLifetimeInSeconds
    }

    // when a peripheral CEN is detected through BLE, it is recorded here
    fun insertCEN(CEN: String) {
        val c = CEN()
        c.CEN = CEN
        c.timeStamp =  (System.currentTimeMillis() / 1000L).toInt()
        cenDao.insert(c)
    }

    // ------- Network API Calls: mapping into Server Endpoints via Retrofit
    // 1. Client publicizes report to /cenreport along with 3 CENKeys (base64 encoded)
    private fun postCENReport(report : CENReport) = cenApi.postCENReport(report)

    // 2. Client periodically gets publicized CENKeys alongside symptoms/infections reports from /exposurecheck
    private fun cenkeysCheck(timestamp : Int) = cenApi.cenkeysCheck(timestamp)

    // 3. Client fetch reports from /cenreport/<cenKey> (base64 encoded)
    private fun getCENReport(cenKey : String) = cenApi.getCENReport(cenKey)

    // doPostSymptoms is called when a ViewModel in the UI sees the user finish a Symptoms Report, the Symptoms + last 3 CENKeys are posted to the server
    fun doPostSymptoms(report : CENReport) {
        val CENKeysStr = lastCENKeys(3)
        CENKeysStr?.let {
            postCENReport(report)
        }
    }

    // lastCENKeys gets the last few CENKeys used to generate CENs by this device
    fun lastCENKeys(lim : Int) : String? {
        val CENKeys = cenkeyDao.lastCENKeys(lim)
        CENKeys?.let {
            if ( CENKeys.size > 0 ) {
                val CENKeysStrings = CENKeys.map{ k -> k.toString() }
                return CENKeysStrings.joinToString(",")
            }
        }
        return null
    }

    fun periodicCENKeysCheck() {
        val call = cenkeysCheck(lastCENKeysCheck)
        call.enqueue(object :
            Callback<CENKeys> {
            override fun onResponse(call: Call<CENKeys?>?, response: Response<CENKeys>) {
                val statusCode: Int = response.code()
                if ( statusCode == 200 ) {
                    val r: CENKeys? = response.body()
                    r?.CENKeys?.let {
                        for ( i in it.indices ) {
                            processMatches(matchCENKey(it[i], lastCENKeysCheck))
                        }
                    }
                } else {
                    log.e("periodicCENKeysCheck $statusCode")
                }
            }

            override fun onFailure(call: Call<CENKeys?>?, t: Throwable?) {
                // Log error here since request failed
                log.e("periodicCENKeysCheck Failure")
            }
        })
        Handler().postDelayed({
            periodicCENKeysCheck()  // TODO: worry about tail recursion / call stack depth?
        }, periodicCENKeysCheckFrequencyInSeconds * 1000L)
    }

    // processMatches fetches CENReport
    fun processMatches(matchedCENs : List<CEN>?) {
        matchedCENs?.let {
            if ( it.size > 0 ) {
                log.i("processMatches MATCH Found")
                for (i in it.indices) {
                    val matchedCENkey = matchedCENs[i]
                    val call = getCENReport(matchedCENkey.CEN)
                    // TODO: for each match fetch Report data and record in Symptoms
                    // cenReportDao.insert(cenReport)
                }
            }
        }
    }

    // matchCENKey uses a publicized key and finds matches with one database call per key
    //  Not efficient... It would be best if all observed CENs are loaded into memory
    fun matchCENKey(key : String, maxTimestamp : Int) : List<CEN>? {
        // take the last 7 days of timestamps and generate all the possible CENs (e.g. 7 days) TODO: Parallelize this?
        val minTimestamp = maxTimestamp - 7*24* CENLifetimeInSeconds
        var possibleCENs = Array<String>(7*24) {i ->
            val ts = maxTimestamp - CENLifetimeInSeconds * i
            val CENBytes = generateCEN(key, ts)
            CENBytes.toString()
        }
        // check if the possibleCENs are in the CEN Table
        return cenDao.matchCENs(minTimestamp, maxTimestamp, possibleCENs)
    }
}
