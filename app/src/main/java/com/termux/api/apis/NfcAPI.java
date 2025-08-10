package com.termux.api.apis;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.TagTechnology;
import android.os.Bundle;
import android.util.JsonWriter;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.api.util.PendingIntentUtils;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// - [x] handle race condition of finish() by Activity.isFinishing()
// inside onNewIntent, first check isFinishing(). If true, immediately quit with exception

// close() finishes the activity

// intent of close() before started? Yes, onCreate and then immediately finish()
// so, special treatment for close()

// connect(): delayed after tag discovery
// before that, connect() is not finished, so no further command is supposed to appear
// in case it appear, no tag, so return exception as it should

class NfcException extends RuntimeException {
    NfcException() {

    }

    NfcException(String message) {
        super(message);
    }
}
class UnexpectedException extends NfcException {
    UnexpectedException(String message) {
        super(message);
    }
}
class UnsupportedTechnology extends NfcException {}
class NoConnectionException extends NfcException {}
class WrongTechnologyException extends NfcException {
    WrongTechnologyException(@NonNull String expected, @NonNull String found) {
        super(String.format("%s expected, but %s found", expected, found));
    }
}
class NfcUnavailableException extends NfcException {}
class ActivityFinishingException extends NfcException {}
class TagNullException extends NfcException {}

class ArgumentException extends NfcException {
    ArgumentException() {
        super();
    }

    ArgumentException(String message) {
        super(message);
    }
}
class ArgNumberException extends ArgumentException {
    ArgNumberException(int expected, int found) {
        super(String.format("%d expected, found %d", expected, found));
    }
}
class InvalidHexException extends ArgumentException {
    InvalidHexException(char c) {
        super(String.format("%c", c));
    }
}
class InvalidHexLengthException extends ArgumentException {}
class InvalidCommandException extends ArgumentException {}

class Utils {
    static void checkTechnologyNonNull(@Nullable TagTechnology technology) throws NoConnectionException {
        if (technology == null) {
            throw new NoConnectionException();
        }
    }

    static void checkTagNonNull(@Nullable Tag tag) throws TagNullException {
        if (tag == null) {
            throw new TagNullException();
        }
    }

    static <T extends TagTechnology> @NonNull T liftTagTechnology(@Nullable TagTechnology technology, @NonNull Class<T> tClass)
            throws NoConnectionException, WrongTechnologyException
    {
        checkTechnologyNonNull(technology);

        try {
            T techLifted = tClass.cast(technology);
            assert techLifted != null;
            return techLifted;
        }
        catch (ClassCastException e) {
            throw new WrongTechnologyException(tClass.getSimpleName(), technology.getClass().getSimpleName());
        }
    }

    static void checkIntEqual(int expectedLength, int argLength) throws ArgNumberException {
        if (expectedLength != argLength) {
            throw new ArgNumberException(expectedLength, argLength);
        }
    }

    static byte parseHexOne(char c) throws ArgumentException {
        if ('0' <= c && c <= '9')
            return (byte)((byte)c - '0');
        if ('a' <= c && c <= 'f')
            return (byte)((byte)c - 'a' + 10);
        if ('A' <= c && c <= 'F')
            return (byte)((byte)c - 'A' + 10);
        throw new InvalidHexException(c);
    })

    static byte parseHexTwo(char high, char low) throws ArgumentException {
        return (byte)((parseHexOne(high) << 4) | parseHexOne(low));
    }

    static byte[] parseHex(@NonNull String s) throws ArgumentException {
        if (s.length()%2 != 0 || s.isEmpty()) {
            throw new InvalidHexLengthException();
        }

        byte[] result = new byte[s.length()/2];
        for (int i=0; i<s.length(); i+=2) {
            result[i/2] = parseHexTwo(s.charAt(i), s.charAt(i+1));
        }

        return result;
    }

    static int parseInt(String number) throws ArgumentException {
        try {
            return Integer.parseInt(number);
        }
        catch (NumberFormatException e) {
            throw new ArgumentException(e.getMessage());
        }
    }

    static String formatHexOne(byte b) {
        return String.format("%02X", b);
    }

    static String formatHex(@NonNull byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b: data) {
            sb.append(Utils.formatHexOne(b));
        }
        return sb.toString();
    }

    static boolean isArrayPrefix(@NonNull String[] full, @NonNull String[] prefix) {
        if (full.length < prefix.length)
            return false;

        for (int i=0; i<prefix.length; ++i) {
            if (!full[i].equals(prefix[i])) {
                return false;
            }
        }

        return true;
    }

    static String[] collectNumberedArgs(@NonNull Intent intent, @NonNull String argPrefix) {
        List<String> result = new ArrayList<>();

        for (int index=1; true; ++index) {
            String argName = argPrefix + index;
            String argValue = intent.getStringExtra(argName);
            if (argValue == null) {
                break;
            }
            result.add(argValue);
        }

        return result.toArray(new String[0]);
    }
}

class CallResult {
    @Nullable Object mData;

    CallResult(@Nullable Object data) {
        mData = data;
    }

    static CallResult successWithString(@NonNull String data) {
        return new CallResult(data);
    }

    static CallResult successWithInt(int data) {
        return new CallResult(data);
    }

    static CallResult successWithBool(boolean data) {
        return new CallResult(data);
    }

    static CallResult success() {
        return new CallResult(null);
    }
}

interface TagTechnologyClosure {
    // return true if finish activity
    CallResult call() throws IOException, android.nfc.FormatException, NfcException;
}

public class NfcAPI {

    private static final String LOG_TAG = "NfcAPI";

    public static void onReceive(final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        Intent newIntent = new Intent(context, NfcActivity.class);
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        newIntent.putExtras(extras).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(newIntent);
    }


    public static class NfcActivity extends AppCompatActivity {
        private TagTechnologyClosure mDelayedClosure;
        private TagTechnology mTechnology;
        private Tag mTag;
        private NfcAdapter mAdapter;
//        static String socket_input;
//        static String socket_output;

        private static final String LOG_TAG = "NfcActivity";

        // start of wrapper for quit
        private final static String[] METHOD_NAME_QUIT = {"quit"};

        private TagTechnologyClosure parseArgsQuit(final @NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_QUIT.length, args.length);
            return this::callQuit;
        }

        private CallResult callQuit() {
            Toast.makeText(this, "callQuit", Toast.LENGTH_SHORT).show();
            finish();
            return CallResult.success();
        }
        // end of wrapper for quit

        // start of wrappers for abstract class TagTechnology
        final static String CLASS_NAME_TAG_TECHNOLOGY = "TagTechnology";

        // start of wrapper for TagTechnology::isConnected
        private final static String[] METHOD_NAME_TAG_TECHNOLOGY_IS_CONNECTED = {CLASS_NAME_TAG_TECHNOLOGY, "isConnected"};

        private TagTechnologyClosure parseArgsIsoDepIsConnected(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_TAG_TECHNOLOGY_IS_CONNECTED.length, args.length);
            return this::callTagTechnologyIsConnected;
        }

        private CallResult callTagTechnologyIsConnected() {
            Utils.checkTechnologyNonNull(mTechnology);
            boolean response = mTechnology.isConnected();
            return CallResult.successWithBool(response);
        }
        // end of wrapper for TagTechnology::isConnected

        // start of wrapper for TagTechnology::close
        private final static String[] METHOD_NAME_TAG_TECHNOLOGY_CLOSE = {CLASS_NAME_TAG_TECHNOLOGY, "close"};

        private TagTechnologyClosure parseArgsTagTechnologyClose(final @NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_TAG_TECHNOLOGY_CLOSE.length, args.length);
            return this::callTagTechnologyClose;
        }

        private CallResult callTagTechnologyClose() throws IOException {
            Utils.checkTechnologyNonNull(mTechnology);
            mTechnology.close();
            return CallResult.success();
        }
        // end of wrapper for TagTechnology::close

        // start of wrapper for TagTechnology::getTag
        private final static String[] METHOD_NAME_TAG_TECHNOLOGY_GET_TAG = {CLASS_NAME_TAG_TECHNOLOGY, "getTag"};

        private TagTechnologyClosure parseArgsTagTechnologyGetTag(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_TAG_TECHNOLOGY_GET_TAG.length, args.length);
            return this::callTagTechnologyGetTag;
        }

        private CallResult callTagTechnologyGetTag() {
            Utils.checkTagNonNull(mTag);
            String description = mTag.toString();
            return CallResult.successWithString(description);
        }
        // end of wrappers for abstract class TagTechnology

        // start of wrappers for class NfcA
        private static final String CLASS_NAME_NFC_A = "NfcA";

        // start of wrapper for NfcA::connect
        private final static String[] METHOD_NAME_NFC_A_CONNECT = {CLASS_NAME_NFC_A, "connect"};

        private TagTechnologyClosure parseArgsNfcAConnect(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_A_CONNECT.length, args.length);
            return this::callNfcAConnect;
        }

        private CallResult callNfcAConnect() throws IOException {
            Utils.checkTagNonNull(mTag);
            mTechnology = NfcA.get(mTag);
            if (mTechnology == null) {
                throw new UnsupportedTechnology();
            }
            mTechnology.connect();
            return CallResult.success();
        }
        // end of wrapper for NfcA::connect

        // start of wrapper for NfcA::getAtqa
        private final static String[] METHOD_NAME_NFC_A_GET_ATQA = {CLASS_NAME_NFC_A, "getAtqa"};

        private TagTechnologyClosure parseArgsNfcAGetAtqa(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_A_GET_ATQA.length, args.length);
            return this::callNfcAGetAtqa;
        }

        private CallResult callNfcAGetAtqa() {
            @NonNull NfcA nfcA = Utils.liftTagTechnology(mTechnology, NfcA.class);
            byte[] response = nfcA.getAtqa();
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcA::getAtqa

        // start of wrapper for NfcA::getMaxTransceiveLength
        private final static String[] METHOD_NAME_NFC_A_GET_MAX_TRANSCEIVE_LENGTH = {CLASS_NAME_NFC_A, "getMaxTransceiveLength"};

        private TagTechnologyClosure parseArgsNfcAGetMaxTransceiveLength(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_A_GET_MAX_TRANSCEIVE_LENGTH.length, args.length);
            return this::callNfcAGetMaxTransceiveLength;
        }

        private CallResult callNfcAGetMaxTransceiveLength() {
            @NonNull NfcA nfcA = Utils.liftTagTechnology(mTechnology, NfcA.class);
            int response = nfcA.getMaxTransceiveLength();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for NfcA::getMaxTransceiveLength

        // start of wrapper for NfcA::getSak
        private final static String[] METHOD_NAME_NFC_A_GET_SAK = {CLASS_NAME_NFC_A, "getSak"};

        private TagTechnologyClosure parseArgsNfcAGetSak(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_A_GET_SAK.length, args.length);
            return this::callNfcAGetSak;
        }

        private CallResult callNfcAGetSak() {
            @NonNull NfcA nfcA = Utils.liftTagTechnology(mTechnology, NfcA.class);
            short response = nfcA.getSak();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for NfcA::getSak

        // start of wrapper for NfcA::getTimeout
        private final static String[] METHOD_NAME_NFC_A_GET_TIMEOUT = {CLASS_NAME_NFC_A, "getTimeout"};

        private TagTechnologyClosure parseArgsNfcAGetTimeout(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_A_GET_TIMEOUT.length, args.length);
            return this::callNfcAGetTimeout;
        }

        private CallResult callNfcAGetTimeout() {
            @NonNull NfcA nfcA = Utils.liftTagTechnology(mTechnology, NfcA.class);
            int response = nfcA.getTimeout();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for NfcA::getTimeout

        // start of wrapper for NfcA::setTimeout
        private final static String[] METHOD_NAME_NFC_A_SET_TIMEOUT = {CLASS_NAME_NFC_A, "setTimeout"};

        private TagTechnologyClosure parseArgsNfcASetTimeout(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_A_SET_TIMEOUT.length + 1, args.length);
            @NonNull int timeout = Utils.parseInt(args[METHOD_NAME_NFC_A_SET_TIMEOUT.length]);
            return () -> callNfcASetTimeout(timeout);
        }

        private CallResult callNfcASetTimeout(int timeout) {
            @NonNull NfcA nfcA = Utils.liftTagTechnology(mTechnology, NfcA.class);
            nfcA.setTimeout(timeout);
            return CallResult.success();
        }
        // end of wrapper for NfcA::setTimeout

        // start of wrapper for NfcA::transceive
        private final static String[] METHOD_NAME_NFC_A_TRANSCEIVE = {CLASS_NAME_NFC_A, "transceive"};

        private TagTechnologyClosure parseArgsNfcATransceive(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_A_TRANSCEIVE.length + 1, args.length);
            @NonNull byte[] data = Utils.parseHex(args[args.length-1]);
            return () -> callNfcATransceive(data);
        }

        private CallResult callNfcATransceive(byte[] data) throws IOException {
            @NonNull NfcA nfcA = Utils.liftTagTechnology(mTechnology, NfcA.class);
            byte[] response = nfcA.transceive(data);
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcA::transceive
        // end of wrappers for class NfcA

        // start of wrappers for class NfcB
        private static final String CLASS_NAME_NFC_B = "NfcB";

        // start of wrapper for NfcB::connect
        private final static String[] METHOD_NAME_NFC_B_CONNECT = {CLASS_NAME_NFC_B, "connect"};

        private TagTechnologyClosure parseArgsNfcBConnect(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_B_CONNECT.length, args.length);
            return this::callNfcBConnect;
        }

        private CallResult callNfcBConnect() throws IOException {
            Utils.checkTagNonNull(mTag);
            mTechnology = NfcB.get(mTag);
            if (mTechnology == null) {
                throw new UnsupportedTechnology();
            }
            mTechnology.connect();
            return CallResult.success();
        }
        // end of wrapper for NfcB::connect

        // start of wrapper for NfcB::getApplicationData
        private final static String[] METHOD_NAME_NFC_B_GET_APPLICATION_DATA = {CLASS_NAME_NFC_B, "getApplicationData"};

        private TagTechnologyClosure parseArgsNfcBGetApplicationData(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_B_GET_APPLICATION_DATA.length, args.length);
            return this::callNfcBGetApplicationData;
        }

        private CallResult callNfcBGetApplicationData() {
            @NonNull NfcB nfcB = Utils.liftTagTechnology(mTechnology, NfcB.class);
            byte[] response = nfcB.getApplicationData();
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcB::getApplicationData

        // start of wrapper for NfcB::getMaxTransceiveLength
        private final static String[] METHOD_NAME_NFC_B_GET_MAX_TRANSCEIVE_LENGTH = {CLASS_NAME_NFC_B, "getMaxTransceiveLength"};

        private TagTechnologyClosure parseArgsNfcBGetMaxTransceiveLength(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_B_GET_MAX_TRANSCEIVE_LENGTH.length, args.length);
            return this::callNfcBGetMaxTransceiveLength;
        }

        private CallResult callNfcBGetMaxTransceiveLength() {
            @NonNull NfcB nfcB = Utils.liftTagTechnology(mTechnology, NfcB.class);
            int response = nfcB.getMaxTransceiveLength();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for NfcB::getMaxTransceiveLength

        // start of wrapper for NfcB::getProtocolInfo
        private final static String[] METHOD_NAME_NFC_B_GET_PROTOCOL_INFO = {CLASS_NAME_NFC_B, "getProtocolInfo"};

        private TagTechnologyClosure parseArgsNfcBGetProtocolInfo(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_B_GET_PROTOCOL_INFO.length, args.length);
            return this::callNfcBGetProtocolInfo;
        }

        private CallResult callNfcBGetProtocolInfo() {
            @NonNull NfcB nfcB = Utils.liftTagTechnology(mTechnology, NfcB.class);
            byte[] response = nfcB.getProtocolInfo();
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcB::getProtocolInfo

        // start of wrapper for NfcB::transceive
        private final static String[] METHOD_NAME_NFC_B_TRANSCEIVE = {CLASS_NAME_NFC_B, "transceive"};

        private TagTechnologyClosure parseArgsNfcBTransceive(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_B_TRANSCEIVE.length + 1, args.length);
            @NonNull byte[] data = Utils.parseHex(args[args.length-1]);
            return () -> callNfcBTransceive(data);
        }

        private CallResult callNfcBTransceive(byte[] data) throws IOException {
            @NonNull NfcB nfcB = Utils.liftTagTechnology(mTechnology, NfcB.class);
            byte[] response = nfcB.transceive(data);
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcB::transceive
        // end of wrappers for class NfcB

        // start of wrappers for class NfcF
        private static final String CLASS_NAME_NFC_F = "NfcF";

        // start of wrapper for NfcF::connect
        private final static String[] METHOD_NAME_NFC_F_CONNECT = {CLASS_NAME_NFC_F, "connect"};

        private TagTechnologyClosure parseArgsNfcFConnect(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_F_CONNECT.length, args.length);
            return this::callNfcFConnect;
        }

        private CallResult callNfcFConnect() throws IOException {
            Utils.checkTagNonNull(mTag);
            mTechnology = NfcF.get(mTag);
            if (mTechnology == null) {
                throw new UnsupportedTechnology();
            }
            mTechnology.connect();
            return CallResult.success();
        }
        // end of wrapper for NfcF::connect

        // start of wrapper for NfcF::getManufacturer
        private final static String[] METHOD_NAME_NFC_F_GET_MANUFACTURER = {CLASS_NAME_NFC_F, "getManufacturer"};

        private TagTechnologyClosure parseArgsNfcFGetManufacturer(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_F_GET_MANUFACTURER.length, args.length);
            return this::callNfcFGetManufacturer;
        }

        private CallResult callNfcFGetManufacturer() {
            @NonNull NfcF nfcF = Utils.liftTagTechnology(mTechnology, NfcF.class);
            byte[] response = nfcF.getManufacturer();
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcF::getManufacturer

        // start of wrapper for NfcF::getMaxTransceiveLength
        private final static String[] METHOD_NAME_NFC_F_GET_MAX_TRANSCEIVE_LENGTH = {CLASS_NAME_NFC_F, "getMaxTransceiveLength"};

        private TagTechnologyClosure parseArgsNfcFGetMaxTransceiveLength(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_F_GET_MAX_TRANSCEIVE_LENGTH.length, args.length);
            return this::callNfcFGetMaxTransceiveLength;
        }

        private CallResult callNfcFGetMaxTransceiveLength() {
            @NonNull NfcF nfcF = Utils.liftTagTechnology(mTechnology, NfcF.class);
            int response = nfcF.getMaxTransceiveLength();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for NfcF::getMaxTransceiveLength

        // start of wrapper for NfcF::getSystemCode
        private final static String[] METHOD_NAME_NFC_F_GET_SYSTEM_CODE = {CLASS_NAME_NFC_F, "getSystemCode"};

        private TagTechnologyClosure parseArgsNfcFGetSystemCode(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_F_GET_SYSTEM_CODE.length, args.length);
            return this::callNfcFGetSystemCode;
        }

        private CallResult callNfcFGetSystemCode() {
            @NonNull NfcF nfcF = Utils.liftTagTechnology(mTechnology, NfcF.class);
            byte[] response = nfcF.getSystemCode();
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcF::getSystemCode

        // start of wrapper for NfcF::getTimeout
        private final static String[] METHOD_NAME_NFC_F_GET_TIMEOUT = {CLASS_NAME_NFC_F, "getTimeout"};

        private TagTechnologyClosure parseArgsNfcFGetTimeout(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_F_GET_TIMEOUT.length, args.length);
            return this::callNfcFGetTimeout;
        }

        private CallResult callNfcFGetTimeout() {
            @NonNull NfcF nfcF = Utils.liftTagTechnology(mTechnology, NfcF.class);
            int response = nfcF.getTimeout();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for NfcF::getTimeout

        // start of wrapper for NfcF::setTimeout
        private final static String[] METHOD_NAME_NFC_F_SET_TIMEOUT = {CLASS_NAME_NFC_F, "setTimeout"};

        private TagTechnologyClosure parseArgsNfcFSetTimeout(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_F_SET_TIMEOUT.length + 1, args.length);
            @NonNull int timeout = Utils.parseInt(args[METHOD_NAME_NFC_F_SET_TIMEOUT.length]);
            return () -> callNfcFSetTimeout(timeout);
        }

        private CallResult callNfcFSetTimeout(int timeout) {
            @NonNull NfcF nfcF = Utils.liftTagTechnology(mTechnology, NfcF.class);
            nfcF.setTimeout(timeout);
            return CallResult.success();
        }
        // end of wrapper for NfcF::setTimeout

        // start of wrapper for NfcF::transceive
        private final static String[] METHOD_NAME_NFC_F_TRANSCEIVE = {CLASS_NAME_NFC_F, "transceive"};

        private TagTechnologyClosure parseArgsNfcFTransceive(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_F_TRANSCEIVE.length + 1, args.length);
            @NonNull byte[] data = Utils.parseHex(args[args.length-1]);
            return () -> callNfcFTransceive(data);
        }

        private CallResult callNfcFTransceive(byte[] data) throws IOException {
            @NonNull NfcF nfcF = Utils.liftTagTechnology(mTechnology, NfcF.class);
            byte[] response = nfcF.transceive(data);
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcF::transceive
        // end of wrappers for class NfcF

        // start of wrappers for class NfcV
        private static final String CLASS_NAME_NFC_V = "NfcV";

        // start of wrapper for NfcV::connect
        private final static String[] METHOD_NAME_NFC_V_CONNECT = {CLASS_NAME_NFC_V, "connect"};

        private TagTechnologyClosure parseArgsNfcVConnect(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_V_CONNECT.length, args.length);
            return this::callNfcVConnect;
        }

        private CallResult callNfcVConnect() throws IOException {
            Utils.checkTagNonNull(mTag);
            mTechnology = NfcV.get(mTag);
            if (mTechnology == null) {
                throw new UnsupportedTechnology();
            }
            mTechnology.connect();
            return CallResult.success();
        }
        // end of wrapper for NfcV::connect

        // start of wrapper for NfcV::getDsfId
        private final static String[] METHOD_NAME_NFC_V_GET_DSF_ID = {CLASS_NAME_NFC_V, "getDsfId"};

        private TagTechnologyClosure parseArgsNfcVGetDsfId(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_V_GET_DSF_ID.length, args.length);
            return this::callNfcVGetDsfId;
        }

        private CallResult callNfcVGetDsfId() {
            @NonNull NfcV nfcV = Utils.liftTagTechnology(mTechnology, NfcV.class);
            byte response = nfcV.getDsfId();
            return CallResult.successWithString(Utils.formatHexOne(response));
        }
        // end of wrapper for NfcV::getDsfId

        // start of wrapper for NfcV::getMaxTransceiveLength
        private final static String[] METHOD_NAME_NFC_V_GET_MAX_TRANSCEIVE_LENGTH = {CLASS_NAME_NFC_V, "getMaxTransceiveLength"};

        private TagTechnologyClosure parseArgsNfcVGetMaxTransceiveLength(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_V_GET_MAX_TRANSCEIVE_LENGTH.length, args.length);
            return this::callNfcVGetMaxTransceiveLength;
        }

        private CallResult callNfcVGetMaxTransceiveLength() {
            @NonNull NfcV nfcV = Utils.liftTagTechnology(mTechnology, NfcV.class);
            int response = nfcV.getMaxTransceiveLength();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for NfcV::getMaxTransceiveLength

        // start of wrapper for NfcV::getResponseFlags
        private final static String[] METHOD_NAME_NFC_V_GET_RESPONSE_FLAGS = {CLASS_NAME_NFC_V, "getResponseFlags"};

        private TagTechnologyClosure parseArgsNfcVGetResponseFlags(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_V_GET_RESPONSE_FLAGS.length, args.length);
            return this::callNfcVGetResponseFlags;
        }

        private CallResult callNfcVGetResponseFlags() {
            @NonNull NfcV nfcV = Utils.liftTagTechnology(mTechnology, NfcV.class);
            byte response = nfcV.getResponseFlags();
            return CallResult.successWithString(Utils.formatHexOne(response));
        }
        // end of wrapper for NfcV::getResponseFlags

        // start of wrapper for NfcV::transceive
        private final static String[] METHOD_NAME_NFC_V_TRANSCEIVE = {CLASS_NAME_NFC_V, "transceive"};

        private TagTechnologyClosure parseArgsNfcVTransceive(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NFC_V_TRANSCEIVE.length + 1, args.length);
            @NonNull byte[] data = Utils.parseHex(args[args.length-1]);
            return () -> callNfcVTransceive(data);
        }

        private CallResult callNfcVTransceive(byte[] data) throws IOException {
            @NonNull NfcV nfcV = Utils.liftTagTechnology(mTechnology, NfcV.class);
            byte[] response = nfcV.transceive(data);
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for NfcV::transceive
        // end of wrappers for class NfcV

        // start of wrappers for class IsoDep
        private static final String CLASS_NAME_ISO_DEP = "IsoDep";

        // start of wrapper for IsoDep::connect
        private final static String[] METHOD_NAME_ISO_DEP_CONNECT = {CLASS_NAME_ISO_DEP, "connect"};

        private TagTechnologyClosure parseArgsIsoDepConnect(final @NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_ISO_DEP_CONNECT.length, args.length);
            return this::callIsoDepConnect;
        }

        private CallResult callIsoDepConnect() throws IOException {
            Utils.checkTagNonNull(mTag);
            Toast.makeText(this, "callIsoDepConnect", Toast.LENGTH_SHORT).show();
            mTechnology = IsoDep.get(mTag);
            if (mTechnology == null) {
                throw new UnsupportedTechnology();
            }
            mTechnology.connect();

            return CallResult.success();
        }
        // end of wrapper for IsoDep::connect

        // start of wrapper for IsoDep::getHiLayerResponse
        private final static String[] METHOD_NAME_ISO_DEP_GET_HI_LAYER_RESPONSE = {CLASS_NAME_ISO_DEP, "getHiLayerResponse"};

        private TagTechnologyClosure parseArgsIsoDepGetHiLayerResponse(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_ISO_DEP_GET_HI_LAYER_RESPONSE.length, args.length);
            return this::callIsoDepGetHiLayerResponse;
        }

        private CallResult callIsoDepGetHiLayerResponse() {
            @NonNull IsoDep isoDep = Utils.liftTagTechnology(mTechnology, IsoDep.class);
            byte[] response = isoDep.getHiLayerResponse();
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for IsoDep::getHiLayerResponse

        // start of wrapper for IsoDep::getHistoricalBytes
        private final static String[] METHOD_NAME_ISO_DEP_GET_HISTORICAL_BYTES = {CLASS_NAME_ISO_DEP, "getHistoricalBytes"};

        private TagTechnologyClosure parseArgsIsoDepGetHistoricalBytes(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_ISO_DEP_GET_HISTORICAL_BYTES.length, args.length);
            return this::callGetHistoricalBytes;
        }

        private CallResult callGetHistoricalBytes() {
            @NonNull IsoDep isoDep = Utils.liftTagTechnology(mTechnology, IsoDep.class);
            byte[] response = isoDep.getHistoricalBytes();
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for IsoDep::getHistoricalBytes

        // start of wrapper for IsoDep::getMaxTransceiveLength
        private final static String[] METHOD_NAME_ISO_DEP_GET_MAX_TRANSCEIVE_LENGTH = {CLASS_NAME_ISO_DEP, "getMaxTransceiveLength"};

        private TagTechnologyClosure parseArgsIsoDepGetMaxTransceiveLength(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_ISO_DEP_GET_MAX_TRANSCEIVE_LENGTH.length, args.length);
            return this::callGetMaxTransceiveLength;
        }

        private CallResult callGetMaxTransceiveLength() {
            @NonNull IsoDep isoDep = Utils.liftTagTechnology(mTechnology, IsoDep.class);
            int response = isoDep.getMaxTransceiveLength();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for IsoDep::getMaxTransceiveLength


        // start of wrapper for IsoDep::getTimeout
        private final static String[] METHOD_NAME_ISO_DEP_GET_MAX_TIMEOUT = {CLASS_NAME_ISO_DEP, "getTimeout"};

        private TagTechnologyClosure parseArgsIsoDepGetTimeout(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_ISO_DEP_GET_MAX_TRANSCEIVE_LENGTH.length, args.length);
            return this::callGetTimeout;
        }

        private CallResult callGetTimeout() {
            @NonNull IsoDep isoDep = Utils.liftTagTechnology(mTechnology, IsoDep.class);
            int response = isoDep.getTimeout();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for IsoDep::getTimeout

        // start of wrapper for IsoDep::isExtendedLengthApduSupported
        private final static String[] METHOD_NAME_ISO_DEP_IS_EXTENDED_LENGTH_APDU_SUPPORTED = {CLASS_NAME_ISO_DEP, "isExtendedLengthApduSupported"};

        private TagTechnologyClosure parseArgsIsoDepIsExtendedLengthApduSupported(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_ISO_DEP_IS_EXTENDED_LENGTH_APDU_SUPPORTED.length, args.length);
            return this::callIsExtendedLengthApduSupported;
        }

        private CallResult callIsExtendedLengthApduSupported() {
            @NonNull IsoDep isoDep = Utils.liftTagTechnology(mTechnology, IsoDep.class);
            boolean response = isoDep.isExtendedLengthApduSupported();
            return CallResult.successWithBool(response);
        }
        // end of wrapper for IsoDep::isExtendedLengthApduSupported

        // start of wrapper for IsoDep::setTimeout
        private final static String[] METHOD_NAME_ISO_DEP_SET_TIMEOUT = {CLASS_NAME_ISO_DEP, "setTimeout"};

        private TagTechnologyClosure parseArgsIsoDepSetTimeout(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_ISO_DEP_SET_TIMEOUT.length + 1, args.length);
            @NonNull int timeout = Utils.parseInt(args[METHOD_NAME_ISO_DEP_SET_TIMEOUT.length]);
            return () -> callSetTimeout(timeout);
        }

        private CallResult callSetTimeout(int timeout) {
            @NonNull IsoDep isoDep = Utils.liftTagTechnology(mTechnology, IsoDep.class);
            isoDep.setTimeout(timeout);
            return CallResult.success();
        }
        // end of wrapper for IsoDep::setTimeout

        // start of wrapper for IsoDep::transceive
        private final static String[] METHOD_NAME_ISO_DEP_TRANSCEIVE = {CLASS_NAME_ISO_DEP, "transceive"};

        private TagTechnologyClosure parseArgsIsoDepTransceive(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_ISO_DEP_TRANSCEIVE.length + 1, args.length);

            @NonNull byte[] requestData = Utils.parseHex(args[args.length-1]);
            return () -> callIsoDepTransceive(requestData);
        }

        private CallResult callIsoDepTransceive(@NonNull byte[] requestData) throws NfcException, IOException, IllegalStateException {
            @NonNull IsoDep isoDep = Utils.liftTagTechnology(mTechnology, IsoDep.class);
            Toast.makeText(this, "start transceive", Toast.LENGTH_SHORT).show();
            @NonNull byte[] response = isoDep.transceive(requestData);
            Toast.makeText(this, "stop transceive", Toast.LENGTH_SHORT).show();
            return CallResult.successWithString(Utils.formatHex(response));
        }
        // end of wrapper for IsoDep::transceive
        // end of wrappers for class IsoDep

        // start of wrappers for class Ndef
        private static final String CLASS_NAME_NDEF = "Ndef";

        // start of wrapper for Ndef::connect
        private final static String[] METHOD_NAME_NDEF_CONNECT = {CLASS_NAME_NDEF, "connect"};

        private TagTechnologyClosure parseArgsNdefConnect(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_CONNECT.length, args.length);
            return this::callNdefConnect;
        }

        private CallResult callNdefConnect() throws IOException {
            Utils.checkTagNonNull(mTag);
            mTechnology = Ndef.get(mTag);
            if (mTechnology == null) {
                throw new UnsupportedTechnology();
            }
            mTechnology.connect();
            return CallResult.success();
        }
        // end of wrapper for Ndef::connect

        // start of wrapper for Ndef::canMakeReadOnly
        private final static String[] METHOD_NAME_NDEF_CAN_MAKE_READ_ONLY = {CLASS_NAME_NDEF, "canMakeReadOnly"};

        private TagTechnologyClosure parseArgsNdefCanMakeReadOnly(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_CAN_MAKE_READ_ONLY.length, args.length);
            return this::callNdefCanMakeReadOnly;
        }

        private CallResult callNdefCanMakeReadOnly() {
            @NonNull Ndef ndef = Utils.liftTagTechnology(mTechnology, Ndef.class);
            boolean response = ndef.canMakeReadOnly();
            return CallResult.successWithBool(response);
        }
        // end of wrapper for Ndef::canMakeReadOnly

        // start of wrapper for Ndef::getCachedNdefMessage
        private final static String[] METHOD_NAME_NDEF_GET_CACHED_NDEF_MESSAGE = {CLASS_NAME_NDEF, "getCachedNdefMessage"};

        private TagTechnologyClosure parseArgsNdefGetCachedNdefMessage(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_GET_CACHED_NDEF_MESSAGE.length, args.length);
            return this::callNdefGetCachedNdefMessage;
        }

        private CallResult callNdefGetCachedNdefMessage() {
            @NonNull Ndef ndef = Utils.liftTagTechnology(mTechnology, Ndef.class);
            NdefMessage msg = ndef.getCachedNdefMessage();
            return CallResult.successWithString(msg.toString());
        }
        // end of wrapper for Ndef::getCachedNdefMessage

        // start of wrapper for Ndef::getMaxSize
        private final static String[] METHOD_NAME_NDEF_GET_MAX_SIZE = {CLASS_NAME_NDEF, "getMaxSize"};

        private TagTechnologyClosure parseArgsNdefGetMaxSize(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_GET_MAX_SIZE.length, args.length);
            return this::callNdefGetMaxSize;
        }

        private CallResult callNdefGetMaxSize() {
            @NonNull Ndef ndef = Utils.liftTagTechnology(mTechnology, Ndef.class);
            int response = ndef.getMaxSize();
            return CallResult.successWithInt(response);
        }
        // end of wrapper for Ndef::getMaxSize

        // start of wrapper for Ndef::getNdefMessage
        private final static String[] METHOD_NAME_NDEF_GET_NDEF_MESSAGE = {CLASS_NAME_NDEF, "getNdefMessage"};

        private TagTechnologyClosure parseArgsNdefGetNdefMessage(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_GET_NDEF_MESSAGE.length, args.length);
            return this::callNdefGetNdefMessage;
        }

        private CallResult callNdefGetNdefMessage() throws IOException, android.nfc.FormatException {
            @NonNull Ndef ndef = Utils.liftTagTechnology(mTechnology, Ndef.class);
            NdefMessage msg = ndef.getNdefMessage();
            return CallResult.successWithString(msg.toString());
        }
        // end of wrapper for Ndef::getNdefMessage

        // start of wrapper for Ndef::getType
        private final static String[] METHOD_NAME_NDEF_GET_TYPE = {CLASS_NAME_NDEF, "getType"};

        private TagTechnologyClosure parseArgsNdefGetType(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_GET_TYPE.length, args.length);
            return this::callNdefGetType;
        }

        private CallResult callNdefGetType() {
            @NonNull Ndef ndef = Utils.liftTagTechnology(mTechnology, Ndef.class);
            String response = ndef.getType();
            return CallResult.successWithString(response);
        }
        // end of wrapper for Ndef::getType

        // start of wrapper for Ndef::isWritable
        private final static String[] METHOD_NAME_NDEF_IS_WRITABLE = {CLASS_NAME_NDEF, "isWritable"};

        private TagTechnologyClosure parseArgsNdefIsWritable(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_IS_WRITABLE.length, args.length);
            return this::callNdefIsWritable;
        }

        private CallResult callNdefIsWritable() {
            @NonNull Ndef ndef = Utils.liftTagTechnology(mTechnology, Ndef.class);
            boolean response = ndef.isWritable();
            return CallResult.successWithBool(response);
        }
        // end of wrapper for Ndef::isWritable

        // start of wrapper for Ndef::makeReadOnly
        private final static String[] METHOD_NAME_NDEF_MAKE_READ_ONLY = {CLASS_NAME_NDEF, "makeReadOnly"};

        private TagTechnologyClosure parseArgsNdefMakeReadOnly(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_MAKE_READ_ONLY.length, args.length);
            return this::callNdefMakeReadOnly;
        }

        private CallResult callNdefMakeReadOnly() throws IOException {
            @NonNull Ndef ndef = Utils.liftTagTechnology(mTechnology, Ndef.class);
            boolean result = ndef.makeReadOnly();
            return CallResult.successWithBool(result);
        }
        // end of wrapper for Ndef::makeReadOnly

        // start of wrapper for Ndef::writeNdefMessage
        private final static String[] METHOD_NAME_NDEF_WRITE_NDEF_MESSAGE = {CLASS_NAME_NDEF, "writeNdefMessage"};

        private TagTechnologyClosure parseArgsNdefWriteNdefMessage(@NonNull String[] args) throws ArgumentException {
            Utils.checkIntEqual(METHOD_NAME_NDEF_WRITE_NDEF_MESSAGE.length + 1, args.length);
            @NonNull byte[] msgHex = Utils.parseHex(args[args.length-1]);
            return () -> callNdefWriteNdefMessage(msgHex);
        }

        private CallResult callNdefWriteNdefMessage(@NonNull byte[] msgHex) throws FormatException, IOException {
            @NonNull Ndef ndef = Utils.liftTagTechnology(mTechnology, Ndef.class);
            ndef.writeNdefMessage(new NdefMessage(msgHex));
            return CallResult.success();
        }
        // end of wrapper for Ndef::writeNdefMessage
        // end of wrappers for class Ndef



        TagTechnologyClosure parseClosureFromArgs(@NonNull String[] args) throws ArgumentException {
            if (Utils.isArrayPrefix(args, METHOD_NAME_ISO_DEP_CONNECT)) {
                return parseArgsIsoDepConnect(args);
            } else if (Utils.isArrayPrefix(args, METHOD_NAME_ISO_DEP_TRANSCEIVE)) {
                return parseArgsIsoDepTransceive(args);
            } else if (Utils.isArrayPrefix(args, METHOD_NAME_TAG_TECHNOLOGY_CLOSE)) {
                return parseArgsTagTechnologyClose(args);
            } else if (Utils.isArrayPrefix(args, METHOD_NAME_QUIT)) {
                return parseArgsQuit(args);
            } else {
                throw new InvalidCommandException();
            }
        }

        TagTechnologyClosure parseClosureFromIntent(@NonNull Intent intent) throws ArgumentException {
            @NonNull String[] args = Utils.collectNumberedArgs(intent, "arg");
            return parseClosureFromArgs(args);
        }


        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            Logger.logDebug(LOG_TAG, "onCreate");

            super.onCreate(savedInstanceState);
            setContentView(new View(this));

            Intent intent = this.getIntent();

            if (intent == null) {
                finish();
                return;
            }

            Toast.makeText(this, "NfcAPI.onCreate!@", Toast.LENGTH_SHORT).show();

            assert intent.hasExtra("socket_input");
            assert intent.hasExtra("socket_output");

            try {
                mDelayedClosure = parseClosureFromIntent(intent);
            }
            catch (ArgumentException e) {
                postException(e);
                Toast.makeText(this, "NfcAPI.onCreate argumentException", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }


//            if (null == socket_input) socket_input = intent.getStringExtra("socket_input");
//            if (null == socket_output) socket_output = intent.getStringExtra("socket_output");

            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter == null || !adapter.isEnabled()) {
                postException(new NfcUnavailableException());
                Toast.makeText(this, "NfcAPI.onCreate adapter unavailable", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            mAdapter = adapter;
        }

        @Override
        protected void onResume() {
            Logger.logVerbose(LOG_TAG, "onResume");
            super.onResume();

            // - https://developer.android.com/develop/connectivity/nfc/advanced-nfc#foreground-dispatch
            Intent intentNew = new Intent(this, NfcActivity.class).addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intentNew,
                    PendingIntentUtils.getPendingIntentMutableFlag());
            IntentFilter[] intentFilter = new IntentFilter[]{
                    new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
                    new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                    new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)};
            mAdapter.enableForegroundDispatch(this, pendingIntent, intentFilter, null);
        }

        private void invokeClosure(TagTechnologyClosure closure) {
            try {
                CallResult result = closure.call();
                postResult(result);
            } catch (Exception e) {
                postException(e);
            }
        }

        private void consumeClosure() {
            assert mDelayedClosure != null;
            invokeClosure(mDelayedClosure);
            mDelayedClosure = null;
        }

        @Override
        protected void onNewIntent(Intent intent) {
            Logger.logDebug(LOG_TAG, "onNewIntent");
            super.onNewIntent(intent);
            Toast.makeText(this, "onNewIntent very begin", Toast.LENGTH_SHORT).show();

            if (isFinishing()) {
                postException(new ActivityFinishingException());
                return;
            }

            // first intent: onCreate -> onResume -> onNewIntent(tag) & doDelayedWork
            // later intents (including connect, except tag discovery): onNewIntent

            // if intent is to connect, but no tag has been saved,
            // then this intent must have been raced between the first


//            intent.putExtra("socket_input", socket_input);
//            intent.putExtra("socket_output", socket_output);

            String action = intent.getAction();
            if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                    || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                    || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
                Toast.makeText(this, "tag discovered", Toast.LENGTH_SHORT).show();
                // only after this will the commands (except close) be processed
                // there are at most one
                Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

                assert tag != null;
                mTag = tag;
                if (mDelayedClosure != null) {
                    consumeClosure();
                }
                return;
            }

            Toast.makeText(this, "other intents", Toast.LENGTH_SHORT).show();
            boolean socket_presence_equal = intent.hasExtra("socket_input") == intent.hasExtra("socket_output");
            boolean socket_presence = intent.hasExtra("socket_input");
            Toast.makeText(this, "presence="+(socket_presence?"true":"false")+",equal="+(socket_presence_equal?"true":"false"), Toast.LENGTH_SHORT).show();
            assert intent.hasExtra("socket_input");
            assert intent.hasExtra("socket_output");
            if (!intent.hasExtra("socket_input")) {
                Toast.makeText(this, "intent no socket: " + intent.getStringExtra("arg1"), Toast.LENGTH_SHORT).show();
                ResultReturner.copyIntentExtras(getIntent(), intent);
            }
            assert intent.hasExtra("socket_input");
            assert intent.hasExtra("socket_output");
            setIntent(intent);
            TagTechnologyClosure closure;
            try {
                closure = parseClosureFromIntent(intent);
            } catch (ArgumentException e) {
                postException(e);
                return;
            }
            Toast.makeText(this, "invoking other intents", Toast.LENGTH_SHORT).show();
            invokeClosure(closure);
        }

        @Override
        protected void onPause() {
            Logger.logDebug(LOG_TAG, "onPause");
            mAdapter.disableForegroundDispatch(this);
            super.onPause();
        }

        @Override
        protected void onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy");
            Toast.makeText(this, "NfcAPI.onDestroy, finishing=" + (isFinishing() ? "true" : "false"), Toast.LENGTH_SHORT).show();
            super.onDestroy();
        }

        void postResult(final CallResult result) {
            ResultReturner.returnData(getApplicationContext(), getIntent(), new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    Logger.logDebug(LOG_TAG, "postResult");
                    out.beginObject();
                    out.name("result").value(result.mData);
                    out.endObject();
                }
            });
        }

        void postException(final Exception e) {
            ResultReturner.returnData(getApplicationContext(), getIntent(), new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("exceptionType").value(e.getClass().getSimpleName());
                    out.name("exceptionMessage").value(e.getMessage());
                    out.endObject();
                }
            });
        }
    }

}
