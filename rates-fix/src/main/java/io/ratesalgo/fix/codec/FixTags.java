package io.ratesalgo.fix.codec;

/**
 * FIX 4.4 tag numbers used by this project, plus the simulator-defined custom tags in the 9500
 * range (API_SPEC.md Section 10.2).
 */
public final class FixTags {

  private FixTags() {}

  public static final int ACCOUNT = 1;
  public static final int AVG_PX = 6;
  public static final int BEGIN_SEQ_NO = 7;
  public static final int BEGIN_STRING = 8;
  public static final int BODY_LENGTH = 9;
  public static final int CHECK_SUM = 10;
  public static final int CL_ORD_ID = 11;
  public static final int CUM_QTY = 14;
  public static final int CURRENCY = 15;
  public static final int END_SEQ_NO = 16;
  public static final int EXEC_ID = 17;
  public static final int EXEC_REF_ID = 19;
  public static final int SECURITY_ID_SOURCE = 22;
  public static final int LAST_PX = 31;
  public static final int LAST_QTY = 32;
  public static final int MSG_SEQ_NUM = 34;
  public static final int MSG_TYPE = 35;
  public static final int NEW_SEQ_NO = 36;
  public static final int ORDER_ID = 37;
  public static final int ORDER_QTY = 38;
  public static final int ORD_STATUS = 39;
  public static final int ORD_TYPE = 40;
  public static final int ORIG_CL_ORD_ID = 41;
  public static final int POSS_DUP_FLAG = 43;
  public static final int PRICE = 44;
  public static final int REF_SEQ_NUM = 45;
  public static final int SECURITY_ID = 48;
  public static final int SENDER_COMP_ID = 49;
  public static final int SENDING_TIME = 52;
  public static final int SIDE = 54;
  public static final int SYMBOL = 55;
  public static final int TARGET_COMP_ID = 56;
  public static final int TEXT = 58;
  public static final int TIME_IN_FORCE = 59;
  public static final int TRANSACT_TIME = 60;
  public static final int VALID_UNTIL_TIME = 62;
  public static final int RPT_SEQ = 83;
  public static final int POSS_RESEND = 97;
  public static final int ENCRYPT_METHOD = 98;
  public static final int ORD_REJ_REASON = 103;
  public static final int HEART_BT_INT = 108;
  public static final int TEST_REQ_ID = 112;
  public static final int QUOTE_ID = 117;
  public static final int ORIG_SENDING_TIME = 122;
  public static final int GAP_FILL_FLAG = 123;
  public static final int EXPIRE_TIME = 126;
  public static final int QUOTE_REQ_ID = 131;
  public static final int BID_PX = 132;
  public static final int OFFER_PX = 133;
  public static final int BID_SIZE = 134;
  public static final int OFFER_SIZE = 135;
  public static final int RESET_SEQ_NUM_FLAG = 141;
  public static final int NO_RELATED_SYM = 146;
  public static final int EXEC_TYPE = 150;
  public static final int LEAVES_QTY = 151;
  public static final int MD_REQ_ID = 262;
  public static final int SUBSCRIPTION_REQUEST_TYPE = 263;
  public static final int MARKET_DEPTH = 264;
  public static final int MD_UPDATE_TYPE = 265;
  public static final int NO_MD_ENTRY_TYPES = 267;
  public static final int NO_MD_ENTRIES = 268;
  public static final int MD_ENTRY_TYPE = 269;
  public static final int MD_ENTRY_PX = 270;
  public static final int MD_ENTRY_SIZE = 271;
  public static final int MD_UPDATE_ACTION = 279;
  public static final int MD_REQ_REJ_REASON = 281;
  public static final int QUOTE_STATUS = 297;
  public static final int QUOTE_CANCEL_TYPE = 298;
  public static final int NUMBER_OF_ORDERS = 346;
  public static final int REF_TAG_ID = 371;
  public static final int REF_MSG_TYPE = 372;
  public static final int SESSION_REJECT_REASON = 373;
  public static final int BUSINESS_REJECT_REASON = 380;
  public static final int CXL_REJ_RESPONSE_TO = 434;
  public static final int PARTY_ID_SOURCE = 447;
  public static final int PARTY_ID = 448;
  public static final int PARTY_ROLE = 452;
  public static final int NO_PARTY_IDS = 453;
  public static final int QUOTE_STATUS_REQ_ID = 649;
  public static final int MASS_STATUS_REQ_ID = 584;
  public static final int MASS_STATUS_REQ_TYPE = 585;
  public static final int QUOTE_RESP_ID = 693;
  public static final int QUOTE_RESP_TYPE = 694;
  public static final int MD_PRICE_LEVEL = 1023;
  public static final int DISPLAY_QTY = 1138;
  public static final int MIN_QTY = 110;

  /** Number of dealers in competition on an RFQ (simulator custom tag). */
  public static final int DEALER_COUNT = 9501;

  /** Cover (second best) price disclosed to the winning dealer (simulator custom tag). */
  public static final int COVER_PRICE = 9502;

  /** RFQ outcome detail on a Done Away QuoteResponse: TA traded away, DA done away. */
  public static final int RFQ_OUTCOME = 9503;

  /** CLOB workup state on an execution report: N none, W workup, X exclusive window. */
  public static final int WORKUP_STATE = 9504;

  /** SEF execution method: R request for quote, O order book. */
  public static final int EXEC_METHOD = 9505;

  /** SEF transaction class: Q required, P permitted. */
  public static final int TRANSACTION_CLASS = 9506;

  /** SEF block trade flag. */
  public static final int BLOCK_TRADE = 9507;

  /** Venue reject reason code (API_SPEC.md Section 11.5). */
  public static final int VENUE_REJECT_CODE = 9510;
}
