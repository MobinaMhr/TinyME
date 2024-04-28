package ir.ramtung.tinyme.messaging;

public class Message {
    public static final String INVALID_ORDER_ID = "Invalid order ID";
    public static final String INVALID_ORDER_REQ = "Invalid order request";
    public static final String ORDER_QUANTITY_NOT_POSITIVE = "Order quantity is not-positive";
    public static final String ORDER_PRICE_NOT_POSITIVE = "Order price is not-positive";
    public static final String UNKNOWN_SECURITY_ISIN = "Unknown security ISIN";
    public static final String ORDER_ID_NOT_FOUND = "Order ID not found in the order book";
    public static final String INVALID_PEAK_SIZE = "Iceberg order peak size is out of range";
    public static final String CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER = "Cannot specify peak size for a non-iceberg order";
    public static final String UNKNOWN_BROKER_ID = "Unknown broker ID";
    public static final String UNKNOWN_SHAREHOLDER_ID = "Unknown shareholder ID";
    public static final String BUYER_HAS_NOT_ENOUGH_CREDIT = "Buyer has not enough credit";
    public static final String QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE = "Quantity is not a multiple of security lot size";
    public static final String PRICE_NOT_MULTIPLE_OF_TICK_SIZE = "Price is not a multiple of security tick size";
    public static final String SELLER_HAS_NOT_ENOUGH_POSITIONS = "Seller has not enough positions";
    public static final String CANNOT_CHANGE_MEQ_DURING_UPDATE = "Cannot change minimum execution quantity during update";
    public static final String ORDER_NOT_MET_MEQ_VALUE = "Order not met minimum execution quantity value";
    public static final String MEQ_NOT_POSITIVE = "Minimum execution quantity is not-positive";
    public static final String STOP_PRICE_NOT_POSITIVE = "Stop price is not-positive";
    public static final String ORDER_CANNOT_BE_ICEBERG_AND_STOP_LIMIT = "Order cannot be iceberg and stop limit";
    public static final String ORDER_CANNOT_HAVE_MEQ_AND_BE_STOP_LIMIT = "Order cannot have minimum execution quantity and be stop limit";
    public static final String MEQ_CANNOT_BE_MORE_THAN_ORDER_QUANTITY = "Minimum execution quantity cannot be more than order quantity";
    public static final String CANNOT_UPDATE_ACTIVE_STOP_LIMIT_ORDER = "cannot update active stop limit order";

}
