package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class AuctionMatcherTest {
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;
    private static final int BROKER_1_CREDIT = 100_000_000;
    private static final int BROKER_2_CREDIT = 20_000_000;
    private Security security;
    private Broker broker1;
    private Broker broker2;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private InactiveOrderBook inactiveOrderBook;
    private List<Order> orders;
    @Autowired
    private Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("ABC").build();

        broker1 = Broker.builder().credit(BROKER_1_CREDIT).brokerId(1).build();
        brokerRepository.addBroker(broker1);
        broker2 = Broker.builder().credit(BROKER_2_CREDIT).brokerId(2).build();
        brokerRepository.addBroker(broker2);

        shareholder = Shareholder.builder().build();
        shareholderRepository.addShareholder(shareholder);
        shareholder.incPosition(security, 100_000);

        orderBook = security.getOrderBook();

        inactiveOrderBook = security.getInactiveOrderBook();

        securityRepository.addSecurity(security);

        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, broker1, shareholder),

                new Order(6, security, Side.SELL, 100, 15800, broker1, shareholder),

                new Order(7, security, Side.SELL, 100, 15810, broker1, shareholder),
                new Order(8, security, Side.SELL, 100, 15820, broker1, shareholder)
        );
        orders.forEach(order -> orderBook.enqueue(order));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15900, broker1.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(enterOrderRq, broker1, shareholder, matcher);
    }
    private void change_matching_state_to(MatchingState new_matching_state) {
        ChangeMatchingStateRq changeStateRq = ChangeMatchingStateRq.createNewChangeMatchingStateRq(
                security.getIsin(), new_matching_state);
        assertThatNoException().isThrownBy(() -> orderHandler.handleChangeMatchingStateRq(changeStateRq));
    }
    ///////////////////////////////////////////////////// update matching state
    @Test
    void check_if_update_matching_state_from_continuous_to_continuous_works_properly() {
        change_matching_state_to(MatchingState.CONTINUOUS);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.CONTINUOUS);

        change_matching_state_to(MatchingState.CONTINUOUS);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.CONTINUOUS);

        verify(eventPublisher,times(2)).publish(any(SecurityStateChangedEvent.class));
    }
    @Test
    void check_if_update_matching_state_from_auction_to_continues_works_properly_when_not_causes_trades() {
        change_matching_state_to(MatchingState.AUCTION);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.AUCTION);

        change_matching_state_to(MatchingState.CONTINUOUS);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.CONTINUOUS);

        verify(eventPublisher,times(2)).publish(any(SecurityStateChangedEvent.class));
    }
    @Test
    void check_if_update_matching_state_from_continuous_to_auction_works_properly() {
        change_matching_state_to(MatchingState.CONTINUOUS);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.CONTINUOUS);

        change_matching_state_to(MatchingState.AUCTION);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.AUCTION);

        verify(eventPublisher,times(2)).publish(any(SecurityStateChangedEvent.class));
    }
    @Test
    void check_if_update_matching_state_from_auction_to_auction_works_properly_when_causes_trades() {
        change_matching_state_to(MatchingState.AUCTION);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 500, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        change_matching_state_to(MatchingState.AUCTION);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.AUCTION);

        verify(eventPublisher,times(2)).publish(any(SecurityStateChangedEvent.class));
        verify(eventPublisher,times(2)).publish(any(TradeEvent.class));

        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNotNull();
        assertThat(orderBook.findByOrderId(Side.BUY,2).getQuantity()).isEqualTo(300);

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - 200 * 15820 - 300 * 15830);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT + 200 * 15820);

        assertThat(orderBook.findByOrderId(Side.BUY,7)).isNull();
        assertThat(orderBook.findByOrderId(Side.BUY,8)).isNull();
    }
    @Test
    void check_if_update_matching_state_from_auction_to_auction_works_properly_when_not_causes_trades() {
        change_matching_state_to(MatchingState.AUCTION);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.AUCTION);

        change_matching_state_to(MatchingState.AUCTION);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.AUCTION);

        verify(eventPublisher,times(2)).publish(any(SecurityStateChangedEvent.class));
    }
    @Test
    void check_if_update_matching_state_from_auction_to_continuous_works_properly_and_causes_trade() {
        change_matching_state_to(MatchingState.AUCTION);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNotNull();

        change_matching_state_to(MatchingState.CONTINUOUS);
        assertThat(security.getCurrentMatchingState()).isEqualTo(MatchingState.CONTINUOUS);

        verify(eventPublisher,times(1)).publish(any(TradeEvent.class));
        verify(eventPublisher,times(2)).publish(any(SecurityStateChangedEvent.class));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNull();
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - 100 * 15810);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT + 100 * 15810);
        assertThat(orderBook.findByOrderId(Side.BUY,7)).isNull();

        EnterOrderRq enterOrderRq1 = EnterOrderRq.createNewOrderRqWithStopPrice(4, security.getIsin(), 4,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 15810);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq1));

        verify(eventPublisher,times(2)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher,times(1)).publish(any(OrderExecutedEvent.class));
        assertThat(orderBook.findByOrderId(Side.BUY,4)).isNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,4)).isNull();
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - 100 * 15820 - 100 * 15810);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT + 100 * 15820 + 100 * 15810);
        assertThat(orderBook.findByOrderId(Side.BUY,8)).isNull();
    }
    @Test
    void check_if_update_matching_state_from_auction_to_continuous_works_properly_and_does_not_cause_any_trade() {
        // TODO
    }
    @Test
    void check_if_update_matching_state_to_auction_activates_stop_limit_orders() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        List<Order> inactiveOrders = List.of(new StopLimitOrder(3, security, Side.BUY, 304,
                15900, broker1, shareholder, 15700));
        inactiveOrders.forEach(order -> inactiveOrderBook.enqueue(order));

        change_matching_state_to(MatchingState.AUCTION);

        assertThat(orderBook.findByOrderId(Side.BUY,3)).isNotNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,3)).isNull();
    }
    @Test
    void check_if_update_matching_state_to_auction_causes_trade_with_activated_stop_limit_order() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15830));

        List<Order> inactiveOrders = List.of(new StopLimitOrder(3, security, Side.BUY, 304,
                15900, broker1, shareholder, 15700));
        inactiveOrders.forEach(order -> inactiveOrderBook.enqueue(order));

        change_matching_state_to(MatchingState.AUCTION);

        verify(eventPublisher,times(1)).publish(any(TradeEvent.class));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT + 100 * 15810);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15810));

        assertThat(orderBook.findByOrderId(Side.BUY,3)).isNotNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,3)).isNull();
    }
    @Test
    void check_if_update_matching_state_to_continuous_activates_stop_limit_orders() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        List<Order> inactiveOrders = List.of(new StopLimitOrder(3, security, Side.BUY, 304,
                15900, broker1, shareholder, 15700));
        inactiveOrders.forEach(order -> inactiveOrderBook.enqueue(order));

        change_matching_state_to(MatchingState.CONTINUOUS);

        assertThat(orderBook.findByOrderId(Side.BUY,3)).isNotNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,3)).isNull();
    }
    @Test
    void check_if_update_matching_state_to_continuous_causes_trade_with_activated_stop_limit_order() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15830));

        List<Order> inactiveOrders = List.of(new StopLimitOrder(3, security, Side.BUY, 304,
                15900, broker1, shareholder, 15700));
        inactiveOrders.forEach(order -> inactiveOrderBook.enqueue(order));

        change_matching_state_to(MatchingState.CONTINUOUS);

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT + 100 * 15810 + 100 * 15900);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15810));

        assertThat(orderBook.findByOrderId(Side.BUY,3)).isNotNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,3)).isNull();
        assertThat(orderBook.findByOrderId(Side.BUY,8)).isNull();
        assertThat(orderBook.findByOrderId(Side.BUY,3).getQuantity()).isEqualTo(204);
    }
    ///////////////////////////////////////////////////// new order
    @Test
    void check_if_new_buy_iceberg_order_is_allowed_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);
        verify(eventPublisher,times(1)).publish(any(SecurityStateChangedEvent.class));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(),
                2, LocalDateTime.now(), Side.BUY, 100, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 10);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher,times(1)).publish(any(OrderAcceptedEvent.class));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNotNull();

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15900));
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
    }
    @Test
    void check_if_new_sell_iceberg_order_is_allowed_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);
        verify(eventPublisher,times(1)).publish(any(SecurityStateChangedEvent.class));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(),
                2, LocalDateTime.now(), Side.SELL, 100, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 10);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher,times(1)).publish(any(OrderAcceptedEvent.class));
        assertThat(orderBook.findByOrderId(Side.SELL,2)).isNotNull();

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
    }
    @Test
    void check_if_new_buy_order_without_enough_credit_in_broker_is_rejected_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);
        verify(eventPublisher,times(1)).publish(any(SecurityStateChangedEvent.class));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100000, 2000, broker1.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher,times(0)).publish(any(OrderAcceptedEvent.class));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNull();

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
    }
    @Test
    void check_if_new_buy_order_with_enough_credit_in_broker_is_allowed_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);
        verify(eventPublisher,times(1)).publish(any(SecurityStateChangedEvent.class));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 10, 2000, broker1.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher, times(1)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(0)).publish(any(TradeEvent.class));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNotNull();
    }
    @Test
    void check_if_new_sell_order_is_allowed_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);
        verify(eventPublisher,times(1)).publish(any(SecurityStateChangedEvent.class));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.SELL, 10, 2000, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher, times(1)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(0)).publish(any(TradeEvent.class));
        assertThat(orderBook.findByOrderId(Side.SELL,2)).isNotNull();
    }
    @Test
    void check_if_new_buy_order_decreases_broker_credit_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);
        verify(eventPublisher,times(1)).publish(any(SecurityStateChangedEvent.class));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 10, 2000, broker1.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT - (10 * 2000));
    }
    @Test
    void check_if_new_sell_order_does_not_decreases_broker_credit_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);
        verify(eventPublisher,times(1)).publish(any(SecurityStateChangedEvent.class));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.SELL, 10, 2000, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
    }
    @Test
    void check_if_new_MEQ_order_is_not_allowed_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(),
                2, LocalDateTime.now(), Side.BUY, 300, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 250);
        MatchResult result = security.newOrder(enterOrderRq, broker2, shareholder, matcher);

        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNull();
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(result.trades()).isEmpty();
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.MEQ_ORDER_IS_NOT_ALLOWED_IN_AUCTION);
    }
    @Test
    void check_if_new_stop_limit_order_order_is_not_allowed_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(),
                2, LocalDateTime.now(), Side.BUY, 100, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 15800);

        MatchResult result = security.newOrder(enterOrderRq, broker2, shareholder, matcher);
        verify(eventPublisher,times(0)).publish(any(OrderAcceptedEvent.class));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNull();

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(result.trades()).isEmpty();
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION);
    }
    ///////////////////////////////////////////////////// update order
    @Test
    void check_if_updating_MEQ_amount_in_MEQ_order_is_not_allowed_in_auction_state() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(),
                3, LocalDateTime.now(), Side.BUY, 350, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 150);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));
        assertThat(orderBook.findByOrderId(Side.BUY,3)).isNotNull();

        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithMEQ(4, security.getIsin(),
                3, LocalDateTime.now(), Side.BUY, 302, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 250);
        assertThatException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(orderBook.findByOrderId(Side.BUY,3)).isNotNull();
    }
    @Test
    void check_if_updating_quantity_in_MEQ_order_is_allowed_in_auction_state() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(),
                3, LocalDateTime.now(), Side.BUY, 350, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 150);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));
        assertThat(orderBook.findByOrderId(Side.BUY,3)).isNotNull();

        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithMEQ(4, security.getIsin(),
                3, LocalDateTime.now(), Side.BUY, 302, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 150);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(orderBook.findByOrderId(Side.BUY,3)).isNotNull();
    }
    @Test
    void check_if_updating_inactive_stop_limit_order_is_not_allowed_in_auction_state() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(),
                2, LocalDateTime.now(), Side.BUY, 300, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 15850);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNull();

        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithStopPrice(4, security.getIsin(),
                2, LocalDateTime.now(), Side.BUY, 200, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 250);
        assertThatException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNull();
    }
    @Test
    void check_if_updating_active_stop_limit_order_is_allowed_in_auction_state() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(),
                2, LocalDateTime.now(), Side.BUY, 300, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0, 11250);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNotNull();

        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithStopPrice(4, security.getIsin(),
                2, LocalDateTime.now(), Side.BUY, 300, 15900, broker2.getBrokerId(),
                shareholder.getShareholderId(), 250);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNotNull();
    }
    ///////////////////////////////////////////////////// delete order
    @Test
    void check_if_delete_order_works_properly_in_auction_state(){
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));
        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 2));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15830));

        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(4, security.getIsin(), Side.BUY, 2);
        assertThatNoException().isThrownBy(() -> orderHandler.handleDeleteOrder(deleteOrderRq));
        verify(eventPublisher).publish(new OrderDeletedEvent(4,2));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT);
    }
    ///////////////////////////////////////////////////// reopening price
    private void change_last_trade_price(int price){
        orderBook.enqueue(new Order(2, security, Side.BUY, 100, price, broker1, shareholder));

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3,
                LocalDateTime.now(), Side.SELL, 100, price, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));
    }

    @Test
    void check_if_reopening_price_is_calculated_properly_when_two_orders_have_the_same_tradable_quantity_but_different_distance_from_last_trade_price() {
        change_last_trade_price(15835);
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq1 = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3,
                LocalDateTime.now(), Side.BUY, 200, 15840, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq1));


        EnterOrderRq enterOrderRq2 = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3,
                LocalDateTime.now(), Side.BUY, 200, 15850, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq2));

        verify(eventPublisher,times(2)).publish(
                (new OpeningPriceEvent(security.getIsin(), 15840, 200)));

        EnterOrderRq enterOrderRq3 = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3,
                LocalDateTime.now(), Side.BUY, 200, 15836, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq3));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15836, 200));
    }

    @Test
    void check_if_reopening_price_is_calculated_properly_when_two_orders_have_the_same_tradable_quantity_and_distance_from_last_trade_price() {
        change_last_trade_price(15835);
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq1 = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3,
                LocalDateTime.now(), Side.BUY, 200, 15840, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq1));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15840, 200));

        EnterOrderRq enterOrderRq2 = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3,
                LocalDateTime.now(), Side.BUY, 200, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq2));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15830, 200));
    }
    @Test
    void check_if_reopening_price_is_calculated_properly_after_entering_new_order_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 500, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15820, 200));
    }
    @Test
    void check_if_reopening_price_is_set_properly_to_default_value_when_no_trade_is_predicted() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(),
                4, LocalDateTime.now(), Side.BUY, 300, 15700, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 0, 0));

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - 300 * 15700);

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(5, security.getIsin(),
                4, LocalDateTime.now(), Side.BUY, 300, 15900, broker1.getBrokerId(),
                shareholder.getShareholderId(),0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(updateOrderRq));

        verify(eventPublisher).publish(new OrderUpdatedEvent(5, 4));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15820, 200));

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - 300 * 15900);
        assertThat(orderBook.findByOrderId(Side.BUY,4)).isNotNull();
    }
    @Test
    void check_if_reopening_price_is_calculated_properly_after_update_order_in_auction_state(){
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(),
                2, LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15830));

        EnterOrderRq enterOrderRq2 = EnterOrderRq.createUpdateOrderRq(4, security.getIsin(), 7,
                LocalDateTime.now(), Side.SELL, 100, 15900, broker1.getBrokerId(),
                shareholder.getShareholderId(),  0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq2));
        verify(eventPublisher).publish(new OrderUpdatedEvent(4,7));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15820, 100));
    }
    @Test
    void check_if_credits_change_properly_after_reopening() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15830));

        List<Order> inactiveOrders = List.of(new StopLimitOrder(3, security, Side.BUY, 304,
                15900, broker1, shareholder, 15700));
        inactiveOrders.forEach(order -> inactiveOrderBook.enqueue(order));

        change_matching_state_to(MatchingState.CONTINUOUS);

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT + (100 * 15810) + (100 * 15900));
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15810));
    }
    @Test
    void check_if_reopening_price_is_calculated_properly_in_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 2));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15830));
    }
    @Test
    void check_if_reopening_price_is_calculated_properly_transferring_to_auction_state() {
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 2));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));

        change_matching_state_to(MatchingState.AUCTION);

        assertThat(matcher.getLastTradePrice()).isEqualTo(matcher.getReopeningPrice());
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT + 100 * 15810);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15810));
    }
    @Test
    void check_if_reopening_price_is_calculated_properly_after_delete_order_in_auction_state(){
        change_matching_state_to(MatchingState.AUCTION);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));
        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 2));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));

        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - (100 * 15830));

        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(4, security.getIsin(), Side.SELL, 7);
        assertThatNoException().isThrownBy(() -> orderHandler.handleDeleteOrder(deleteOrderRq));
        verify(eventPublisher).publish(new OrderDeletedEvent(4,7));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15820, 100));
    }
    ///////////////////////////////////////////////////// iceberg order
    @Test
    void check_if_iceberg_order_loses_priority_in_auction_match() {
        change_matching_state_to(MatchingState.AUCTION);

        security.getOrderBook().removeByOrderId(Side.BUY,1);
        security.getOrderBook().removeByOrderId(Side.SELL,8);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, broker2.getBrokerId(),
                shareholder.getShareholderId(), 0);
        assertThatNoException().isThrownBy(() -> orderHandler.handleEnterOrder(enterOrderRq));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));
        assertThat(orderBook.findByOrderId(Side.BUY,2)).isNotNull();

        change_matching_state_to(MatchingState.AUCTION);

        orderBook.enqueue(new IcebergOrder(2, security, Side.SELL, 90, 15700,
                broker1, shareholder,50));
        orderBook.enqueue(new IcebergOrder(3, security, Side.SELL, 100, 15700,
                broker1, shareholder,40));
        orderBook.enqueue(new Order(1, security, Side.BUY, 80, 15900, broker2, shareholder));

        change_matching_state_to(MatchingState.AUCTION);

        verify(eventPublisher,times(3)).publish(any(SecurityStateChangedEvent.class));
        verify(eventPublisher,times(3)).publish(any(TradeEvent.class));

        assertThat(orderBook.findByOrderId(Side.BUY,1)).isNull();
        assertThat(orderBook.findByOrderId(Side.SELL,2)).isNotNull();
        assertThat(orderBook.findByOrderId(Side.SELL,3)).isNotNull();
        assertThat(orderBook.findByOrderId(Side.SELL,2).getQuantity()).isEqualTo(40);
        assertThat(orderBook.findByOrderId(Side.SELL,3).getQuantity()).isEqualTo(10);

        assertThat(broker2.getCredit()).isEqualTo(BROKER_2_CREDIT - 100 * 15810);
        assertThat(broker1.getCredit()).isEqualTo(BROKER_1_CREDIT + 80 * 15900 + 100 * 15810);
    }
    /////////////////////////////////////////////////////
}