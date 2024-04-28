package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivateEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
public class StopLimitOrderTest {
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
    private static final int MAIN_BROKER_CREDIT = 100_000_000;
    private Security security;
    private Broker broker;
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

        broker = Broker.builder().credit(MAIN_BROKER_CREDIT).brokerId(1).build();
        shareholder = Shareholder.builder().build();
        shareholderRepository.addShareholder(shareholder);
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();
        inactiveOrderBook = security.getInactiveOrderBook();
        securityRepository.addSecurity(security);

        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, broker, shareholder),
                new Order(6, security, Side.SELL, 100, 15800, broker, shareholder),
                new Order(7, security, Side.SELL, 100, 15810, broker, shareholder),
                new Order(8, security, Side.SELL, 100, 15820, broker, shareholder)
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
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,2)).isNotNull();
    }

    @Test
    void check_if_sell_order_enqueues_to_inActive_queues() {
        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
        brokerRepository.addBroker(testBroker);
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.SELL, 200, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15);

        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit);
        assertThat(inactiveOrderBook.findByOrderId(Side.SELL,2)).isNotNull();
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
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,2)).isNull();
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
    @Test
    void check_if_inActive_buy_order_updates_price() {
        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 200, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15950);

        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (200 * 15900));
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,2)).isNotNull();

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithStopPrice(4, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 200, 15951, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 15950);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(updateOrderRq.getPrice()).isEqualTo(15951);

    }
    @Test
    void check_if_inActive_buy_order_updates_quantity() {
        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 200, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15950);

        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (200 * 15900));
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,2)).isNotNull();

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithStopPrice(4, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 250, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 15950);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(updateOrderRq.getQuantity()).isEqualTo(250);
    }
    @Test
    void check_if_inActive_buy_order_is_deleted() {
        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 200, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15950);

        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (200 * 15900));
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,2)).isNotNull();

        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(4, security.getIsin(), Side.BUY, 2);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));
        assertThat(inactiveOrderBook.findByOrderId(deleteOrderRq.getSide(),deleteOrderRq.getOrderId())).isNull();
    }
    @Test
    void check_if_updated_stop_limit_order_remains_in_inActive_queue() {
        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 200, 15700, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15950);

        assertThatNoException().isThrownBy(() -> security.newOrder(enterOrderRq, testBroker, shareholder, matcher));

        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (200 * 15700));
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,2)).isNotNull();

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithStopPrice(4, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 250, 15700, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 15950);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));

        assertThat(inactiveOrderBook.findByOrderId(Side.BUY,2)).isNotNull();
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (250 * 15700));
    }

    @Test
    void check_if_buy_inactive_queue_is_sorted_correctly() {
        int testBrokerCredit = 20_000_000;

        Broker testBroker = Broker.builder().credit(testBrokerCredit).brokerId(2).build();
        brokerRepository.addBroker(testBroker);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(4, security.getIsin(), 3,
                LocalDateTime.now(), Side.BUY, 90, 15810, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15810);

        EnterOrderRq enterOrderRq2 = EnterOrderRq.createNewOrderRqWithStopPrice(5, security.getIsin(), 4,
                LocalDateTime.now(), Side.BUY, 80, 15810, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15809);

        EnterOrderRq enterOrderRq3 = EnterOrderRq.createNewOrderRq(6, security.getIsin(), 5,
                LocalDateTime.now(), Side.BUY, 10, 15810, 2,
                shareholder.getShareholderId(), 0);

        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish(new OrderAcceptedEvent(4, 3));
        orderHandler.handleEnterOrder(enterOrderRq2);
        verify(eventPublisher).publish(new OrderAcceptedEvent(5, 4));
        orderHandler.handleEnterOrder(enterOrderRq3);

        verify(eventPublisher).publish(new OrderActivateEvent(6, 4));
        verify(eventPublisher).publish(new OrderActivateEvent(6, 3));

        verify(eventPublisher,times(3)).publish(any(OrderExecutedEvent.class));

        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + (100 * 15810));
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - ((10 + 90 + 80) * 15810));
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 5)).isNull();
        assertThat(orderBook.getBuyQueue().size()).isEqualTo(2);
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 3)).isNull();
    }

    @Test
    void check_if_activation_function_activates_other_buy_inActives() {
        int testBrokerCredit = 20_000_000;

        Broker testBroker = Broker.builder().credit(testBrokerCredit).brokerId(2).build();
        brokerRepository.addBroker(testBroker);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(4, security.getIsin(), 3,
                LocalDateTime.now(), Side.BUY, 90, 15820, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15810);

        EnterOrderRq enterOrderRq2 = EnterOrderRq.createNewOrderRqWithStopPrice(5, security.getIsin(), 4,
                LocalDateTime.now(), Side.BUY, 110, 15820, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15790);


        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish(new OrderAcceptedEvent(4, 3));
        orderHandler.handleEnterOrder(enterOrderRq2);
        verify(eventPublisher).publish(new OrderAcceptedEvent(5, 4));
        verify(eventPublisher).publish(new OrderActivateEvent(5, 4));
        verify(eventPublisher,times(2)).publish(any(OrderExecutedEvent.class));
        verify(eventPublisher).publish(new OrderActivateEvent(5, 3));

        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + (100 * 15820 + 100 * 15810));
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (90 * 15820 + 100 * 15810 + 10 * 15820));

        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 3)).isNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 4)).isNull();
        assertThat(orderBook.findByOrderId(Side.BUY, 3)).isNull();
        assertThat(orderBook.findByOrderId(Side.BUY, 4)).isNull();
        assertThat(orderBook.findByOrderId(Side.SELL, 7)).isNull();
        assertThat(orderBook.findByOrderId(Side.SELL, 8)).isNull();
    }
    @Test
    void check_if_activation_function_activates_other_sell_inActives() {
        int testBrokerCredit = 20_000_000;

        Broker testBroker = Broker.builder().credit(testBrokerCredit).brokerId(2).build();
        brokerRepository.addBroker(testBroker);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(4, security.getIsin(), 3,
                LocalDateTime.now(), Side.SELL, 90, 15600, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 16000);

        EnterOrderRq enterOrderRq2 = EnterOrderRq.createNewOrderRqWithStopPrice(5, security.getIsin(), 4,
                LocalDateTime.now(), Side.SELL, 110, 15600, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 16000);


        orderHandler.handleEnterOrder(enterOrderRq);
        orderHandler.handleEnterOrder(enterOrderRq2);
        verify(eventPublisher).publish(new OrderAcceptedEvent(4, 3));
        verify(eventPublisher).publish(new OrderAcceptedEvent(5, 4));
        verify(eventPublisher).publish(new OrderActivateEvent(4, 3));
        verify(eventPublisher).publish(new OrderActivateEvent(5, 4));


        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit + (90 * 15700 + 110 * 15700));

        assertThat(inactiveOrderBook.findByOrderId(Side.SELL, 3)).isNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.SELL, 4)).isNull();
        assertThat(orderBook.findByOrderId(Side.BUY, 1)).isNotNull();
        assertThat(orderBook.findByOrderId(Side.SELL, 6)).isNull();
        assertThat(orderBook.findByOrderId(Side.SELL, 7)).isNotNull();
        assertThat(orderBook.findByOrderId(Side.SELL, 8)).isNotNull();


        EnterOrderRq enterOrderRq3 = EnterOrderRq.createNewOrderRqWithStopPrice(6, security.getIsin(), 5,
                LocalDateTime.now(), Side.BUY, 10, 15820, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15000);

        orderHandler.handleEnterOrder(enterOrderRq3);
        verify(eventPublisher).publish(new OrderAcceptedEvent(6, 5));
        verify(eventPublisher).publish(new OrderActivateEvent(6, 5));
        verify(eventPublisher,times(3)).publish(any(OrderExecutedEvent.class));

        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + (10 * 15810));
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit + (90 * 15700 + 110 * 15700) - (10 * 15810));

        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 5)).isNull();
        assertThat(orderBook.findByOrderId(Side.SELL, 7)).isNotNull();
    }
    @Test
    void check_if_update_inActive_buy_order_activates_others() {
        int testBrokerCredit = 200_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).brokerId(2).build();
        brokerRepository.addBroker(testBroker);


        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 200, 15820, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15810);

        EnterOrderRq enterOrderRq1 = EnterOrderRq.createNewOrderRqWithStopPrice(4, security.getIsin(), 3,
                LocalDateTime.now(), Side.BUY, 10, 15830, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15809);

        orderHandler.handleEnterOrder(enterOrderRq);
        orderHandler.handleEnterOrder(enterOrderRq1);
        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 2));
        verify(eventPublisher).publish(new OrderAcceptedEvent(4, 3));

        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 3)).isNotNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 2)).isNotNull();

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRqWithStopPrice(5, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15820, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 15790);
        orderHandler.handleEnterOrder(updateOrderRq);
        verify(eventPublisher).publish(new OrderActivateEvent(5, 2));
        verify(eventPublisher).publish(new OrderActivateEvent(5, 3));
        verify(eventPublisher,times(2)).publish(any(OrderExecutedEvent.class));
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 3)).isNull();
        assertThat(inactiveOrderBook.findByOrderId(Side.BUY, 2)).isNull();
        assertThat(orderBook.findByOrderId(Side.BUY, 3)).isNull();
        assertThat(orderBook.findByOrderId(Side.BUY, 2)).isNull();
        assertThat(orderBook.findByOrderId(Side.SELL, 7)).isNull();
        assertThat(orderBook.findByOrderId(Side.SELL, 8)).isNotNull();

        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + (100 * 15810 + 10 * 15820));
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (100 * 15810 + 10 * 15820));

    }
}

