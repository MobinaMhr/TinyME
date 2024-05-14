package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
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
    private Security security;
    private Shareholder shareholder;
    private Broker broker1;
    private Broker broker2;
    private Broker broker3;
    ////////////////////////////////////
    private Broker broker;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    private Matcher matcher;
    ////////////////////////////////////
    private static final int MAIN_BROKER_CREDIT = 100_000_000;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker = Broker.builder().credit(MAIN_BROKER_CREDIT).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();

        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, broker, shareholder),
                new Order(6, security, Side.SELL, 100, 15800, broker, shareholder),
                new Order(7, security, Side.SELL, 100, 15810, broker, shareholder)
        );
        orders.forEach(order -> orderBook.enqueue(order));
    }

    @Test
    void check_if_buy_order_did_not_meet_meq_gets_rejected() {
        ChangeMatchingStateRq changeStateRq = ChangeMatchingStateRq.createNewChangeMatchingStateRq(
                security.getIsin(), MatchingState.AUCTION);
        orderHandler.handleChangeMatchingStateRq(changeStateRq);

        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 300, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 250);

        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);

        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(result.trades()).isEmpty();

        System.out.println(result.outcome());
//
//        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED_IN_AUCTION);
//
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 2)).isNotNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6)).isNotNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 7)).isNotNull();
//
//        changeStateRq = ChangeMatchingStateRq.createNewChangeMatchingStateRq(
//                security.getIsin(), MatchingState.CONTINUOUS);
//        orderHandler.handleChangeMatchingStateRq(changeStateRq);
//
////        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit);
////        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
////        assertThat(result.trades()).isEmpty();
////
////        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED_IN_AUCTION);
////
////        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 2)).isNotNull();
////        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6)).isNotNull();
////        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 7)).isNotNull();
    }


//    @Test
//    void check_if_iceberg_buy_order_did_not_meet_meq_gets_rejected() {
//        int testBrokerCredit = 20_000_000;
//        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
//
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.BUY, 300, 15900, testBroker.getBrokerId(),
//                shareholder.getShareholderId(), 100, 250);
//
//        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
//        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit);
//        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
//        assertThat(result.trades()).isEmpty();
//        assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_MET_MEQ_VALUE);
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 2)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6)).isNotNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 7)).isNotNull();
//    }
//    @Test
//    void check_if_buy_order_met_meq_gets_accepted_and_queued() {
//        int testBrokerCredit = 20_000_000;
//        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
//
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.BUY, 300, 15900, testBroker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 150);
//
//        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
//        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - ((100 * 15800) + (100 * 15810) + (100 * 15900)));
//        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + ((100 * 15800) + (100 * 15810)));
//        assertThat(result.trades()).isNotEmpty();
//        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 2)).isNotNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 7)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 2).getMinimumExecutionQuantity()).isEqualTo(150);
//    }
//    @Test
//    void check_if_iceberg_buy_order_met_meq_gets_accepted_and_queued() {
//        int testBrokerCredit = 20_000_000;
//        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
//
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.BUY, 300, 15900, testBroker.getBrokerId(),
//                shareholder.getShareholderId(), 100, 150);
//
//        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
//        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - ((100 * 15800) + (100 * 15810) + (100 * 15900)));
//        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + ((100 * 15800) + (100 * 15810)));
//        assertThat(result.trades()).isNotEmpty();
//        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 2)).isNotNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 7)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 2).getMinimumExecutionQuantity()).isEqualTo(150);
//    }
//    @Test
//    void check_if_buy_order_gets_treated_correct_when_meq_and_order_quantity_are_equal() {
//        int testBrokerCredit = 20_000_000;
//        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
//
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.BUY, 200, 15900, testBroker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 200);
//
//        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
//        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - ((100 * 15800) + (100 * 15810)));
//        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + ((100 * 15800) + (100 * 15810)));
//        assertThat(result.trades()).isNotEmpty();
//        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 2)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 7)).isNull();
//    }
//    @Test
//    void check_update_sell_order_with_same_meq() {
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.SELL, 400, 15700, broker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 50);
//        MatchResult result = security.newOrder(enterOrderRq, broker, shareholder, matcher);
//
//        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.SELL, 500, 15700, broker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 50);
//        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
//    }
//    @Test
//    void check_if_update_buy_order_that_changes_order_meq_gets_rejected() {
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY,1).getMinimumExecutionQuantity()).isEqualTo(0);
//        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithMEQ(1, security.getIsin(), 1, LocalDateTime.now(),
//                Side.BUY, 440, 15450, 0, 0, 0, 100);
//
//        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
//        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 1).getMinimumExecutionQuantity())
//                .isEqualTo(0);
//    }
//    @Test
//    void check_if_enter_buy_order_request_with_meq_less_than_zero_gets_rejected() {
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.BUY, 200, 15900, broker.getBrokerId(),
//                shareholder.getShareholderId(), 0, -5);
//
//        orderHandler.handleEnterOrder(enterOrderRq);
//        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
//        verify(eventPublisher).publish(orderRejectedCaptor.capture());
//        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
//        assertThat(outputEvent.getOrderId()).isEqualTo(2);
//        assertThat(outputEvent.getErrors()).contains(
//                Message.MEQ_NOT_POSITIVE
//        );
//    }
//    @Test
//    void check_if_enter_buy_order_request_with_meq_more_than_order_quantity_gets_rejected() {
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.BUY, 200, 15900, broker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 300);
//
//        orderHandler.handleEnterOrder(enterOrderRq);
//        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
//        verify(eventPublisher).publish(orderRejectedCaptor.capture());
//        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
//        assertThat(outputEvent.getOrderId()).isEqualTo(2);
//        assertThat(outputEvent.getErrors()).contains(
//                Message.MEQ_CANNOT_BE_MORE_THAN_ORDER_QUANTITY
//        );
//
//    }
//    @Test
//    void check_if_sell_order_did_not_meet_meq_gets_rejected() {
//        int testBrokerCredit = 20_000_000;
//        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
//
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.SELL, 500, 15650, testBroker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 400);
//
//        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
//        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit);
//        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
//        assertThat(result.trades()).isEmpty();
//        assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_MET_MEQ_VALUE);
//
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 2)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 1)).isNotNull();
//    }
//    @Test
//    void check_if_sell_order_met_meq_gets_accepted_and_queued() {
//        int testBrokerCredit = 20_000_000;
//        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
//
//        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithMEQ(3, security.getIsin(), 2,
//                LocalDateTime.now(), Side.SELL, 300, 15700, testBroker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 150);
//
//        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
//        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit + (300*15700));
//        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
//        assertThat(result.trades()).isNotEmpty();
//        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
//
//        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 2)).isNull();
//        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 1)).isNotNull();
//    }
}
