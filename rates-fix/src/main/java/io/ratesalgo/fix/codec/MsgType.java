package io.ratesalgo.fix.codec;

/**
 * FIX 4.4 MsgType values as ints: a one-character type is its ASCII code, a two-character type
 * is {@code (first << 8) | second}. Comparing ints keeps the parser free of string handling.
 */
public final class MsgType {

  private MsgType() {}

  public static final int HEARTBEAT = '0';
  public static final int TEST_REQUEST = '1';
  public static final int RESEND_REQUEST = '2';
  public static final int REJECT = '3';
  public static final int SEQUENCE_RESET = '4';
  public static final int LOGOUT = '5';
  public static final int EXECUTION_REPORT = '8';
  public static final int ORDER_CANCEL_REJECT = '9';
  public static final int LOGON = 'A';
  public static final int NEW_ORDER_SINGLE = 'D';
  public static final int ORDER_CANCEL_REQUEST = 'F';
  public static final int ORDER_CANCEL_REPLACE_REQUEST = 'G';
  public static final int QUOTE_REQUEST = 'R';
  public static final int QUOTE = 'S';
  public static final int MARKET_DATA_REQUEST = 'V';
  public static final int MARKET_DATA_SNAPSHOT = 'W';
  public static final int MARKET_DATA_INCREMENTAL = 'X';
  public static final int MARKET_DATA_REQUEST_REJECT = 'Y';
  public static final int QUOTE_CANCEL = 'Z';
  public static final int QUOTE_STATUS_REQUEST = 'a';
  public static final int BUSINESS_MESSAGE_REJECT = 'j';
  public static final int ORDER_MASS_STATUS_REQUEST = ('A' << 8) | 'F';
  public static final int QUOTE_REQUEST_REJECT = ('A' << 8) | 'G';
  public static final int QUOTE_STATUS_REPORT = ('A' << 8) | 'I';
  public static final int QUOTE_RESPONSE = ('A' << 8) | 'J';

  /** True for the session-level (administrative) message types. */
  public static boolean isAdmin(final int msgType) {
    return msgType == HEARTBEAT
        || msgType == TEST_REQUEST
        || msgType == RESEND_REQUEST
        || msgType == REJECT
        || msgType == SEQUENCE_RESET
        || msgType == LOGOUT
        || msgType == LOGON;
  }

  /** The FIX text of a type, for diagnostics and tests (allocates). */
  public static String toText(final int msgType) {
    if (msgType > 0xFF) {
      return new String(new char[] {(char) (msgType >> 8), (char) (msgType & 0xFF)});
    }
    return String.valueOf((char) msgType);
  }

  /** Parses FIX text such as {@code "AJ"} into the int form. */
  public static int fromText(final String text) {
    if (text.length() == 1) {
      return text.charAt(0);
    }
    if (text.length() == 2) {
      return (text.charAt(0) << 8) | text.charAt(1);
    }
    throw new IllegalArgumentException("MsgType must be one or two characters: " + text);
  }
}
