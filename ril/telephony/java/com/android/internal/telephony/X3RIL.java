package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemService;

import android.telephony.PhoneNumberUtils;
import android.telephony.SignalStrength;

public class X3RIL extends RIL implements CommandsInterface {
    private boolean sentHwBootstrap = false;

    public X3RIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
    }

    @Override
    public void
    getIMEI(Message result) {
        //RIL_REQUEST_LGE_SEND_COMMAND 0
        RILRequest rrLSC = RILRequest.obtain(
                0x113, null);
        rrLSC.mParcel.writeInt(1);
        rrLSC.mParcel.writeInt(0);
        send(rrLSC);

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMEI, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
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

    static final int RIL_UNSOL_LGE_BATTERY_LEVEL_UPDATE = 1050;
    static final int RIL_UNSOL_LGE_XCALLSTAT = 1053;
    static final int RIL_UNSOL_LGE_RESTART_RILD = 1055;
    static final int RIL_UNSOL_LGE_SELECTED_SPEECH_CODEC = 1074;
    static final int RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW = 1061;

    private void restartRild() {
        setRadioState(RadioState.RADIO_UNAVAILABLE);
        SystemService.stop("ril-daemon");
        RILRequest.resetSerial();

        // Clear request list
        clearRequestList(RADIO_NOT_AVAILABLE, false);
        // Thread sleeps are ususally a terrible idea, but we do want the radio
        // stack to back off for a bit
        SystemService.start("ril-daemon");
        try {
            Thread.sleep(500);
        } catch (InterruptedException ie) {}
        setRadioState(RadioState.RADIO_ON);
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_BATTERY_LEVEL_UPDATE: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_XCALLSTAT: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_SELECTED_SPEECH_CODEC: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_RESTART_RILD: ret =  responseVoid(p); break;
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
                }
                return;
            case RIL_UNSOL_LGE_RESTART_RILD:
                restartRild();
                break;
            case RIL_UNSOL_LGE_BATTERY_LEVEL_UPDATE:
            case RIL_UNSOL_LGE_XCALLSTAT:
            case RIL_UNSOL_LGE_SELECTED_SPEECH_CODEC:
                if (RILJ_LOGD) riljLog("sinking LGE request > " + response);
                break;
            case RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccStatusChangedRegistrants != null) {
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
        }
    }

	@Override
    protected RILRequest
    processSolicited (Parcel p) {
        int serial, error;
        boolean found = false;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        serial = p.readInt();
        error = p.readInt();

        RILRequest rr = null;

        /* Pre-process the reply before popping it */
        synchronized (mRequestList) {
            for (int i = 0, s = mRequestList.size() ; i < s ; i++) {
                RILRequest tr = mRequestList.get(i);
                if (tr.mSerial == serial) {
                    if (error == 0 || p.dataAvail() > 0) {
                        try {switch (tr.mRequest) {
                            /* Get those we're interested in */
                            case 0x113:
                                rr = tr;
                                break;
                        }} catch (Throwable thr) {
                            // Exceptions here usually mean invalid RIL responses
                            if (rr.mResult != null) {
                                AsyncResult.forMessage(rr.mResult, null, thr);
                                rr.mResult.sendToTarget();
                            }
                            return rr;
                        }
                    } 
                }
            }
        }

        if (rr == null) {
            /* Nothing we care about, go up */
            p.setDataPosition(dataPosition);

            // Forward responses that we are not overriding to the super class
            return super.processSolicited(p);
        }


        rr = findAndRemoveRequestFromList(serial);

        if (rr == null) {
            return null;
        }

        Object ret = null;

        if (error == 0 || p.dataAvail() > 0) {
            switch (rr.mRequest) {
                case 0x113: ret =  responseVoid(p); break;
                default:
                    throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            }
            //break;
        }

        switch (rr.mRequest) {
            case 0x113:
                riljLog(rr.serialString() + "< LGE_SEND_COMMAND"
                        + " " + retToString(rr.mRequest, ret));
                /* COMMAND 1 inits hardware-related properties. We
                 * only need it once per power cycle */
                if (!sentHwBootstrap) {
                    RILRequest rrLSC = RILRequest.obtain(
                            0x113, null);
                    rrLSC.mParcel.writeInt(1);
                    rrLSC.mParcel.writeInt(1);
                    send(rrLSC);
                    sentHwBootstrap = true;
                }
                break;
        }


        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }

        return rr;
    }

}

	@Override
    protected Object responseSignalStrength(Parcel p) {
        int numInts = 12;
        int response[];

        // This is a mashup of algorithms used in
        // SamsungQualcommUiccRIL.java

        // Get raw data
        response = new int[numInts];
        for (int i = 0; i < numInts; i++) {
            response[i] = p.readInt();
        }

		// response[1]=0 -- gsm
		// response[1]=99 -- 3G

		/*
		 * 	[signalStrength=137, 			-- 		response[0]
		 *	 bitErrorRate=99,    			-- 		response[1]	            
		 *	 CDMA_SS.dbm=-1,				-- 		response[2]
		 *	 CDMA_SSecio=-1,    			-- 		response[3]            			
		 *	 EVDO_SS.dbm=-1,				-- 		response[4]
		 *	 EVDO_SS.ecio=-1,   			-- 		response[5]             
		 *	 EVDO_SS.signalNoiseRatio=-1,   -- 		response[6]             
		 *	 LTE_SS.signalStrength=-1,		-- 		response[7]
		 *	 LTE_SS.rsrp=-1,				-- 		response[8]
		 *	 LTE_SS.rsrq=-1,                -- 		response[9]
		 *	 LTE_SS.rssnr=-1,				-- 		response[10]
		 *	 LTE_SS.cqi=-1]					-- 		response[11]
		 */
	
		int gsmSignalStrength = response[0];

		// if is TD signal, do a convert
		if (gsmSignalStrength >= 100) {

		  if (gsmSignalStrength % 2 == 0) {

		  	if (gsmSignalStrength <= 165) {

				if (gsmSignalStrength > 103)
					gsmSignalStrength++;
				else
					gsmSignalStrength = 103;

			} else {
				gsmSignalStrength = 165;
			}
		  }

		  gsmSignalStrength = gsmSignalStrength - 103 >> 1;
		  response[0] = gsmSignalStrength;
		}

        // RIL_LTE_SignalStrength
        if (response[7] == 99) {
            // If LTE is not enabled, clear LTE results
            // 7-11 must be -1 for GSM signal strength to be used (see
            // frameworks/base/telephony/java/android/telephony/SignalStrength.java)
            response[8] = -1;
            response[9] = -1;
            response[10] = -1;
            response[11] = -1;
        }

		boolean isGsm = (mPhoneType == RILConstants.GSM_PHONE);

        return new SignalStrength(response[0], 
			response[1], response[2], response[3], response[4], response[5], 
			response[6], response[7], response[8], response[9], response[10], response[11], isGsm);

    }

}

