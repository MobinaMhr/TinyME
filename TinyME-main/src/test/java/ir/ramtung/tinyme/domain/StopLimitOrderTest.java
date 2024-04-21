package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
public class StopLimitOrderTest {

    private static final int MAIN_BROKER_CREDIT = 100_000_000;
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    private Matcher matcher;
    @Autowired
    private OrderHandler orderHandler;
    @Autowired
    private EventPublisher eventPublisher;

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

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15900, broker.getBrokerId(),
                shareholder.getShareholderId(), 0);
        MatchResult result = security.newOrder(enterOrderRq, broker, shareholder, matcher);

    }

    @Test
    void check_if_buy_order_enqueues_to_inActive_queues() {
        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 200, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15950);

        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (200 * 15900));
        assertThat(orderBook.findByOrderIdForInactiveQueue(Side.BUY,2)).isNotNull();
    }

    @Test
    void check_if_sell_order_enqueues_to_inActive_queues() {
        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.SELL, 200, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15);

        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit);
        assertThat(orderBook.findByOrderIdForInactiveQueue(Side.SELL,2)).isNotNull();
    }
    @Test
    void check_if_buy_order_enqueues_to_buy_queues() {
        int testBrokerCredit = 20_000_000;

        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15800);

        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + (100 * 15810));
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (100 * 15810));
        assertThat(orderBook.findByOrderIdForInactiveQueue(Side.BUY,2)).isNull();
    }

    @Test
    void check_if_enter_buy_order_request_with_stop_price_less_than_zero_gets_rejected() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createUpdateOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 200, 15900, broker.getBrokerId(),
                shareholder.getShareholderId(), -1);

        orderHandler.handleEnterOrder(enterOrderRq);
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(2);
        assertThat(outputEvent.getErrors()).contains(
                Message.STOP_PRICE_NOT_POSITIVE
        );
    }
}
