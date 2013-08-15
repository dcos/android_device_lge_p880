package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.content.Intent;

import android.os.Message;
import android.os.Parcel;
import android.os.Handler;
import android.os.Registrant;
import android.os.SystemProperties;

import android.telephony.PhoneNumberUtils;

import android.text.TextUtils;

import java.lang.Thread;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

public class X3RIL extends RIL implements CommandsInterface {

    protected Context mContext;

    /* EternityProject: Unsolicited responses */
    static final int RIL_UNSOL_LGE_STK_PROACTIVE_SESSION_STATUS = 1041;
    static final int RIL_UNSOL_LGE_NETWORK_REGISTRATION_ERROR =   1047;
    static final int RIL_UNSOL_LGE_PBREADY =                      1049;
    static final int RIL_UNSOL_LGE_BATTERY_LEVEL_UPDATE =         1050;
    static final int RIL_UNSOL_LGE_FACTORY_TEST_REQUEST =         1052;
    static final int RIL_UNSOL_LGE_XCALLSTAT =                    1053;
    static final int RIL_UNSOL_LGE_RESTART_RILD =                 1055;
    static final int RIL_UNSOL_LGE_RESPONSE_PS_SIGNALING_STATUS = 1058;
    static final int RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW =        1061;
    static final int RIL_UNSOL_LGE_SELECTED_SPEECH_CODEC =        1074;
    static final int RIL_UNSOL_LGE_FACTORY_READY =                1080;

    /* EternityProject: Solicited responses */
    static final int RIL_REQUEST_LGE_GET_SERVICE_CENTER_ADDR =    128;
    static final int RIL_REQUEST_LGE_GET_MODEM_INFO =             246;
    static final int RIL_REQUEST_LGE_SEND_COMMAND =               0x113;
    static final int RIL_REQUEST_LGE_SET_GPRS_MOBILE_CLASS =      0x99;

    /* Other variables */
    static final String SELECTED_SPEECH_CODEC = "SelectedSpeechCodec";
    private static String sRilRecoveryType = "UNKNOWN";
    private static boolean isCPcrash = false;

    public X3RIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
        mContext = context;
    }

    public void
    lgeSendCommand(int commandNumber) {
        RILRequest rrLSC = RILRequest.obtain(
              RIL_REQUEST_LGE_SEND_COMMAND, null);

        rrLSC.mParcel.writeInt(0x1);
        rrLSC.mParcel.writeInt(commandNumber);

        if (RILJ_LOGD) {
            StringBuilder sb = new StringBuilder();
            sb.append(rrLSC.serialString());
            sb.append("> ");
            sb.append(requestToString(rrLSC.mRequest));
            sb.append(" ");
            sb.append(commandNumber);

            riljLog(sb.toString());
        }
        send(rrLSC);
    }

    static public boolean
    isRILRecoveryEnable() {
        return SystemProperties.get("ril.eprjrecovery").equals("1");
    }

    static public boolean
    isRILRecoveryProcessing() {
       return SystemProperties.get("ril.reset_progress").equals("1");
    }

    static public boolean
    isRILRecoveryProcessingData() {
        return SystemProperties.get("ril.reset_progress_data").equals("1");
    }

    static public String
    getRILRecoveryType() {
        String type = sRilRecoveryType;

        sRilRecoveryType = "UNKNOWN";

        return type;
    }

    static public void
    setRILRecoveryType(String type) {
        sRilRecoveryType = type;
        return;
    }

    public void
    setATREADY() {
        if (RILJ_LOGD) riljLog("EternityProject: setATREADY");
        lgeSendCommand(0);
    }

    protected void
    doRilRecovery() {
        if (RILJ_LOGD) riljLog("EPRJ-RILJ: doRilRecovery()");

        if (isRILRecoveryProcessing())
            riljLog("EternityProject RIL Recovery is processing... doRilRecovery() end ###");
        else
            lgeSendCommand(0x2);

        return;
    }

    private static void WriteFile(String file, String contents)
    {
        try
        {
            PrintWriter pr = new PrintWriter(file, "ISO-8859-1");
            try
            {
                pr.write(contents);
            }
            finally
            {
                pr.close();
            }
        }
        catch (IOException e)
        {
        }
    }

    protected void
    eprjSetRadioPower(boolean enable) {
        if (RILJ_LOGD) {
            StringBuilder sb = new StringBuilder();
            sb.append("EternityProject: eprjSetRadioPower -- ");
            sb.append(enable ? "powering up" : "powering down");
            sb.append(" Radio HW.");
            riljLog(sb.toString());
        }

        WriteFile("/sys/devices/platform/baseband_xmm_power/xmm_onoff", (enable ? "1" : "0") + '\n');
    }

    protected void
    eprjRadioPowerCycle() {
        try {
            eprjSetRadioPower(false);
            Thread.sleep(250);
            eprjSetRadioPower(true);
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    protected void
    eprjHandleSpeechCodec(Object oldret) {
        if (RILJ_LOGD) riljLog("EternityProject: Speech Codec Handle");

        if (null == oldret) {
            riljLog("EPRJ: ERROR: eprjHandleSpeechCodec: ret is NULL!!!");
            return;
        }

        Intent SelectedSpeechCodecIntent = new Intent("com.lge.ril.SELECTED_SPEECH_CODEC");
        String SelectedSpeechCodecString = (String)oldret;
        int SelectedSpeechCodecNumber = Integer.parseInt(SelectedSpeechCodecString);

        if (RILJ_LOGD) {
            StringBuilder sb = new StringBuilder();
            sb.append("EPRJ: SelectedSpeechCodecNumber = ");
            sb.append(SelectedSpeechCodecNumber);
            riljLog(sb.toString());
        }

        SelectedSpeechCodecIntent.putExtra("SelectedSpeechCodec", SelectedSpeechCodecNumber);
        mContext.sendBroadcast(SelectedSpeechCodecIntent);
    }

    @Override
    public void
    queryCallForwardStatus(int cfReason, int serviceClass,
                String number, Message response) {
        RILRequest rr
            = RILRequest.obtain(RIL_REQUEST_QUERY_CALL_FORWARD_STATUS, response);

        rr.mParcel.writeInt(2); // 2 is for query action, not in use anyway
        rr.mParcel.writeInt(cfReason);
        if (serviceClass == 0)
            serviceClass = 255;
        rr.mParcel.writeInt(serviceClass);
        rr.mParcel.writeInt(PhoneNumberUtils.toaFromString(number));
        rr.mParcel.writeString(number);
        rr.mParcel.writeInt (0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + cfReason + " " + serviceClass);

        send(rr);
    }

    private boolean
    isRildState(String state) {
        String daemon_state = SystemProperties.get("init.svc.ril-daemon");

        StringBuilder sb = new StringBuilder();
        sb.append("isRildState init.svc.ril-daemon : ");
        sb.append(daemon_state);

        if (RILJ_LOGD) riljLog(sb.toString());

        if (daemon_state.equals(state))
           return true;
        else
           return false;
    }

    private void
    clearRequestsList(int error, boolean loggable) {
        int count = mRequestList.size();

        if (loggable) {
            StringBuilder sb = new StringBuilder();
            sb.append("WAKE_LOCK_TIMEOUT  mReqPending=");
            sb.append(mRequestMessagesPending);
            sb.append(" mRequestList=");
            sb.append(count);
            riljLog("EternityProject RIL: " + sb.toString());
        }

        for (int i = 0; i <= count; i++) {
            RILRequest rr = mRequestList.get(i);
            if (loggable) {
                StringBuilder sbr = new StringBuilder();
                sbr.append(i);
                sbr.append(": [");
                sbr.append(rr.mSerial);
                sbr.append("] ");
                sbr.append(requestToString(rr.mRequest));
                riljLog("EternityProject RIL: " + sbr.toString());
            }
            rr.onError(error, null);
            rr.release();
        }
        mRequestList.clear();
        mRequestMessagesWaiting = 0;

        return;
    }

    protected void
    lgeRestartRild() {
        int retry2 = 0;

        if (RILJ_LOGD) {
            riljLog("*********EPRJ_RILJ: LgeRestartRild Start");
            riljLog("### Show RIL Recovery popup ###");
        }

        try {
            if (isRILRecoveryEnable())
                if (isRILRecoveryProcessing()) {
                    if (isCPcrash) {
                        if (RILJ_LOGD) riljLog("EternityProject: CP Crash occurred during RIL recovery!!!!");
                        isCPcrash = false;
                        return;
                    }
                    if (RILJ_LOGD) riljLog("### RIL Recovery is processing... LgeRestartRild() end ###");
                    return;
                } else { 
                    // Baseband is not available while recovery is in progress. Avoid SMS/calls loss.
                    setRadioState(RadioState.RADIO_UNAVAILABLE);
                    SystemProperties.set("ril.reset_progress", "1");
                    SystemProperties.set("ril.reset_progress_data", "1");
                    SystemProperties.set("ctl.stop", "ril-daemon");
                    Thread.sleep(80);

                    while (true) {
                        if (isRildState("stopped")) {
                            eprjRadioPowerCycle();
                            SystemProperties.set("ctl.start", "ril-daemon");
                            retry2 = 0;
                            break;
                        }
                        Thread.sleep(500);
                        if (RILJ_LOGD) riljLog("*********EternityProject: restartRild: Wait until stop");
                        ++retry2;
                        if (retry2 > 20)
                            break;
                    }

                    while (true) {
                        if (isRildState("running")) {
                           // We powercycled the baseband, now it is available.
                           setRadioState(RadioState.RADIO_ON);
                           retry2 = 0;
                           break;
                        }
                        Thread.sleep(500);
                        if (RILJ_LOGD) riljLog("*********EternityProject: restartRild: Wait until running");
                        ++retry2;
                        if (retry2 > 20)
                            break;
                    }
                }
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        try {
            if (retry2 > 10) {
                if (RILJ_LOGD) riljLog("*********EternityProject: restartRild: (Desperately) Start Rild Again");

                SystemProperties.set("ctl.start", "ril-daemon");
                retry2 = 0;

                eprjRadioPowerCycle();

                while (true) {
                    if (isRildState("running") == true) {
                       setRadioState(RadioState.RADIO_ON);
                       retry2 = 0;
                       break;
                    }
                    Thread.sleep(500);
                    if (RILJ_LOGD) riljLog("*********EternityProject: restartRild: Wait until running");
                    ++retry2;
                   if (retry2 > 20) {
                        if (RILJ_LOGD) riljLog("*********EternityProject: FATAL: Unable to restart RILD! Giving up!");
                        break;
                    }
                }
            }
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        // Build the intent and notify RIL recovery is in progress.
        Intent intent = new Intent("lge.ril.intent.action.notify_ril_recovery");
        String type = getRILRecoveryType();
        intent.putExtra("lge.ril.intent.action.notify_ril_recovery", type);
        mContext.sendBroadcast(intent);

        // Reset RIL
        RILRequest.resetSerial();
        clearRequestsList(1, false);

        riljLog("*********EternityProject: restartRild: END.");
        return;
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_PBREADY: ret =  responseVoid(p);
            case RIL_UNSOL_LGE_BATTERY_LEVEL_UPDATE: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_XCALLSTAT: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_RESTART_RILD: ret =  responseString(p); break;
            case RIL_UNSOL_LGE_RESPONSE_PS_SIGNALING_STATUS: ret = responseVoid(p); break;
            case RIL_UNSOL_LGE_SELECTED_SPEECH_CODEC: ret =  responseString(p); break;
            case RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW: ret =  responseVoid(p); break;
            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);

                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p);
                return;
        }
        switch(response) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                /* has bonus radio state int */
                RadioState newState = getRadioStateFromInt(p.readInt());
                p.setDataPosition(dataPosition);
                super.processUnsolicited(p);
                if (RadioState.RADIO_ON == newState) {
                    setNetworkSelectionModeAutomatic(null);
                    SystemProperties.set("ctl.start", "insmod_rawip");
                } else if (RadioState.RADIO_UNAVAILABLE == newState) {
                    SystemProperties.set("ctl.start", "rmmod_rawip");
                    eprjRadioPowerCycle();
                }
                return;
            case RIL_UNSOL_LGE_PBREADY:
                if (RILJ_LOGD) riljLog("EternityProject: RIL_UNSOL_LGE_PBREADY");
                setATREADY();
                break;
            case RIL_UNSOL_LGE_RESTART_RILD:
                if (RILJ_LOGD) riljLog("EternityProject: RILD needs to be restarted!");
                setRILRecoveryType((String)ret);
                lgeRestartRild();
                break;
            case RIL_UNSOL_LGE_RESPONSE_PS_SIGNALING_STATUS:
                if (RILJ_LOGD) riljLog("EternityProject: sinking LGE Signaling Status request");
                break;
            case RIL_UNSOL_LGE_XCALLSTAT:
            case RIL_UNSOL_LGE_BATTERY_LEVEL_UPDATE:
                if (RILJ_LOGD) riljLog("sinking LGE request > " + response);
                break;
            case RIL_UNSOL_LGE_SELECTED_SPEECH_CODEC:
                eprjHandleSpeechCodec(ret);
                break;
            case RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccStatusChangedRegistrants != null) {
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
        }
    }

}
