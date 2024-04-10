package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;// Added by me

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import net.bytebuddy.asm.Advice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class BrokerCreditTest {
    private Security security;
    private Broker buyer;
    private long buyerCredit;
    private Broker seller;
    private long sellerCredit;
    private Shareholder shareholder;
    private List<Order> orders;
    @Autowired
    private Matcher matcher;
    int requestId = 0;
    void checkCreditValues(long buyerExpectedCredit, long sellerExpectedCredit) {
        assertThat(buyer.getCredit()).isEqualTo(buyerExpectedCredit);
        assertThat(seller.getCredit()).isEqualTo(sellerExpectedCredit);
    }
    void logCreditValues() {
        System.out.println("Buyer Credit : " + buyer.getCredit());
        System.out.println("Seller Credit : " + seller.getCredit());
    }
    EnterOrderRq createEnterIceBergOrderRq(int reqId, Broker broker, int orderId, int quantity, int price, int peakSize) {
        Side brokerSide = (broker == buyer) ? Side.BUY : Side.SELL;
        return EnterOrderRq.createNewOrderRq(reqId, security.getIsin(), orderId, LocalDateTime.now(), brokerSide,
                quantity, price, broker.getBrokerId(), shareholder.getShareholderId(), peakSize);
    }
    EnterOrderRq createEnterOrderRq(int reqId, Broker broker, int orderId, int quantity, int price) {
        return createEnterIceBergOrderRq(reqId, broker, orderId, quantity, price, 0);
    }
    EnterOrderRq createUpdateIceBergOrderRq(int reqId, Broker broker, int orderId, int quantity, int price, int peakSize) {
        Side brokerSide = (broker == buyer) ? Side.BUY : Side.SELL;
        return EnterOrderRq.createUpdateOrderRq(reqId, security.getIsin(), orderId, LocalDateTime.now(), brokerSide,
                quantity, price, broker.getBrokerId(), shareholder.getShareholderId(), peakSize);
    }
    EnterOrderRq createUpdateOrderRq(int reqId, Broker broker, int orderId, int quantity, int price) {
        return createUpdateIceBergOrderRq(reqId, broker, orderId, quantity, price, 0);
    }
    DeleteOrderRq createDeleteOrderRq(int reqId, Side brokerSide, int orderId) {
        return new DeleteOrderRq(reqId, security.getIsin(), brokerSide, orderId);
    }
    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        buyerCredit = 10_000_000L;
        buyer = Broker.builder().brokerId(0).credit(buyerCredit).build();
        sellerCredit = 10_000_000L;
        seller = Broker.builder().brokerId(0).credit(sellerCredit).build();
        shareholder = Shareholder.builder().shareholderId(0).build();
        shareholder.incPosition(security, 10_000_000);
        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, buyer, shareholder),
                new Order(2, security, Side.BUY, 43, 15500, buyer, shareholder),
                new Order(3, security, Side.BUY, 445, 15450, buyer, shareholder),
                new Order(4, security, Side.BUY, 526, 15450, buyer, shareholder),
                new Order(5, security, Side.BUY, 1000, 15400, buyer, shareholder),
                new Order(6, security, Side.SELL, 350, 15800, seller, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, seller, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, seller, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, seller, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, seller, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
    }
    @Test
    void testUpdateBuyerOrderQuantityAndPriceMatchesCompletelyWithFirstThreeSellOrders() {
        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, buyer, 1, 850, 16000);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit+304*15700-(350*15800+500*15810),
                sellerCredit+(350*15800+285*15810+215*15810));
    }
    @Test
    void testNoMatchAfterUpdateBuyOrderPrice() {
        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, buyer, 4, 526, 15750);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit-526*300, sellerCredit);
    }
    @Test
    void testUpdateBuyerOrderPriceMatchesCompletely() {
        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, buyer, 1, 304, 15800);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit-30400, sellerCredit+304*15800);
    }
    @Test
    void testCreateAndUpdateBuyOrderSavesCreditCorrectlyInProcessAndMatchesCompletelyV1() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, buyer, 11, 351, 15809);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(buyerCredit-(350*15800+15809),sellerCredit+350*15800);

        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, buyer, 11, 1, 15815);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit-(350*15800+15810),sellerCredit+350*15800+15810);
    }
    @Test
    void testCreateAndUpdateBuyOrderSavesCreditCorrectlyInProcessAndMatchesCompletelyV2() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, buyer, 11, 351, 15805);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(buyerCredit-(350*15800+15805),sellerCredit+350*15800);

        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, buyer, 11, 1, 15810);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit-(350*15800+15810),sellerCredit+350*15800+15810);
    }
    @Test
    void testUpdateBuyerOrderPriceMatchesCompletelyWithFirstSellOrder() {
        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, buyer, 2, 43, 15836);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit+43*15500-43*15800, sellerCredit+43*15800);
    }
    @Test
    void testNewBuyerMatchesCompletelyAfterUpdateSellerOrderQuantityAndPrice() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, buyer, 11, 450, 15800);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(buyerCredit-450*15800, sellerCredit+350*15800);

        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, seller, 7, 150, 15800);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit-450*15800, sellerCredit+350*15800+100*15800);
    }
    @Test
    void testNoMatchAfterUpdateSellOrderPrice() {
        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, seller, 10, 88, 15750);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit, sellerCredit);
    }
    @Test
    void testUpdateSellOrderAndMatchDoneWithFirstBuyOrder() {
        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, seller, 10, 88, 15650);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit, sellerCredit+88*15700);
    }
    @Test
    void testUpdateSellerOrderPriceMatchesCompletelyWithFirstThreeBuyOrders() {
        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, seller, 6, 350, 15000);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit, sellerCredit+5485650);
    }
    @Test
    void testIceBergOrderMatchesCompletelyFirstWithSellOrder() {
        Order iceBergOrder = new IcebergOrder(11, security, Side.BUY, 50, 15850, buyer, shareholder, 100);
        assertThatNoException().isThrownBy(() -> matcher.execute(iceBergOrder));
        checkCreditValues(buyerCredit-50*15800, sellerCredit+50*15800);
    }
    @Test
    void testIcebergMatchesCompletelyWithIcebergOrder() {
        Order iceBergOrder = new IcebergOrder(11, security, Side.BUY, 100, 15750, buyer, shareholder, 10);
        assertThatNoException().isThrownBy(() -> security.getOrderBook().enqueue(iceBergOrder));
        checkCreditValues(buyerCredit, sellerCredit);

        EnterOrderRq orderRq = createEnterIceBergOrderRq(++requestId, seller, 12, 100, 13000, 10);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, seller, shareholder, matcher));
        checkCreditValues(10000000, 11575000);

        EnterOrderRq updateOrderRq = createUpdateIceBergOrderRq(++requestId, buyer, 11, 200, 1200, 10);
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
    }
    @Test
    void testIcebergPartiallyMatchesWithFirstOrder() {
        Order iceBergOrder = new IcebergOrder(11, security, Side.BUY, 100, 15750, buyer, shareholder, 10);
        assertThatNoException().isThrownBy(() -> security.getOrderBook().enqueue(iceBergOrder));
        checkCreditValues(buyerCredit, sellerCredit);

        EnterOrderRq orderRq = createEnterIceBergOrderRq(++requestId, seller, 12, 20, 13000, 10);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(10315000, 10000000);

        EnterOrderRq updateOrderRq = createUpdateIceBergOrderRq(++requestId, buyer, 11, 200, 1200, 10);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(11563000, 10000000);
    }
    @Test
    void testIcebergCompletelyMatchesWithFirstTwoOrders() {
        Order iceBergOrder = new IcebergOrder(11, security, Side.BUY, 400, 15800, buyer, shareholder, 10);
        assertThatNoException().isThrownBy(() -> security.getOrderBook().enqueue(iceBergOrder));
        checkCreditValues(buyerCredit, sellerCredit);

        EnterOrderRq orderRq = createEnterIceBergOrderRq(++requestId, seller, 12, 20, 13000, 10);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(10316000, 10000000);

        EnterOrderRq updateOrderRq = createUpdateIceBergOrderRq(++requestId, buyer, 11, 200, 1200, 10);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(16308000, 10000000);
    }
    @Test
    void testUpdateCompletelyMatchedOrderThrowsException() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, buyer, 11, 100, 15850);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(buyerCredit-100*15800,sellerCredit+100*15800);

        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, buyer, 11, 315, 15850);
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
    }
    @Test
    void testSellerOrderDelete() {
        DeleteOrderRq deleteOrderRq = createDeleteOrderRq(++requestId, Side.SELL, 6);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));
        checkCreditValues(buyerCredit, sellerCredit);
    }
    @Test
    void testBuyerOrderDelete() {
        DeleteOrderRq deleteOrderRq = createDeleteOrderRq(++requestId, Side.BUY, 1);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));
        checkCreditValues(buyerCredit+304*15700, sellerCredit);
    }
    @Test
    void testRollBackAfterUpdatingIntoUnaffordableAmount() {
        EnterOrderRq updateOrderRq = createUpdateOrderRq(++requestId, buyer, 1, 1000, 35000);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        checkCreditValues(buyerCredit, sellerCredit);
    }
    @Test
    void testNewBuyerOrderOfferingBiggerPrice() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, buyer, 11, 100, 15900);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(buyerCredit-100*15800, sellerCredit+100*15800);
    }
    @Test
    void testNewBuyerOrderOfferingEqualPrice() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, buyer, 11, 100, 15800);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(buyerCredit-100*15800, sellerCredit+100*15800);
    }
    @Test
    void testNewBuyerOrderOfferingSmallerPrice() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, buyer, 11, 100, 15700);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(buyerCredit-100*15700, sellerCredit);
    }
    @Test
    void testNewSellerOrderOfferingBiggerPrice() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, seller, 11, 100, 15900);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, buyer, shareholder, matcher));
        checkCreditValues(buyerCredit, sellerCredit);
    }
    @Test
    void testNewSellerOrderOfferingEqualPrice() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, seller, 11, 100, 15700);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, seller, shareholder, matcher));
        checkCreditValues(buyerCredit, sellerCredit+15700*100);
    }
    @Test
    void testNewSellerOrderOfferingSmallerPrice() {
        EnterOrderRq orderRq = createEnterOrderRq(++requestId, seller, 11, 100, 15600);
        assertThatNoException().isThrownBy(() -> security.newOrder(orderRq, seller, shareholder, matcher));
        checkCreditValues(buyerCredit, sellerCredit+15700*100);
    }
    @Test
    void testSaveBrokerCreditAmount() {
        assertThat(buyer.getCredit()).isEqualTo(buyerCredit);
    }
    @Test
    void testDecreaseCreditBySufficientAmountV1() {
        assertThatNoException().isThrownBy(() -> buyer.decreaseCreditBy(100));
        assertThat(buyer.getCredit()).isEqualTo(buyerCredit-100);
    }
    @Test
    void testDecreaseCreditBySufficientAmountV2() {
        assertThatNoException().isThrownBy(() -> buyer.decreaseCreditBy(0));
        assertThat(buyer.getCredit()).isEqualTo(buyerCredit);
    }
    @Test
    void testIncreaseCreditBySufficientAmountV1() {
        assertThatNoException().isThrownBy(() -> buyer.increaseCreditBy(100));
        assertThat(buyer.getCredit()).isEqualTo(buyerCredit+100);
    }
    @Test
    void testIncreaseCreditBySufficientAmountV2() {
        assertThatNoException().isThrownBy(() -> buyer.increaseCreditBy(0));
        assertThat(buyer.getCredit()).isEqualTo(buyerCredit);
    }
    @Test
    void testIncreaseCreditByInsufficientAmount() {
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() ->
                buyer.increaseCreditBy(-100));
    }
    @Test
    void testDecreaseCreditByInsufficientAmount() {
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() ->
                buyer.decreaseCreditBy(-100));
    }
    @Test
    void testHasEnoughCreditSufficientAmountV1() {
        assertThat(buyer.hasEnoughCredit(buyerCredit)).isEqualTo(true);
    }
    @Test
    void testHasEnoughCreditSufficientAmountV2() {
        assertThat(buyer.hasEnoughCredit(buyerCredit-100)).isEqualTo(true);
    }
    @Test
    void testHasEnoughCreditInsufficientAmount() {
        assertThat(buyer.hasEnoughCredit(buyerCredit+100)).isEqualTo(false);
    }
    @Test
    void testSaveOrderPrice() {
        Order order = orders.get(0);
        assertThat(order.getPrice()).isEqualTo(15700);
    }
}

