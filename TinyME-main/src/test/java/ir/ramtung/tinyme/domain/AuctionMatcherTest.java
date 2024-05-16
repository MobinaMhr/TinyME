package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.jgroups.SuspectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        brokerRepository.addBroker(broker);
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
        security.newOrder(enterOrderRq, broker, shareholder, matcher);
    }

    @Test
    void check_if_MEQ_order_is_not_allowed_in_auction() {
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
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.MEQ_ORDER_IS_NOT_ALLOWED_IN_AUCTION);
    }

    @Test
    void check_if_stop_limit_order_order_is_not_allowed_in_auction() {
        ChangeMatchingStateRq changeStateRq = ChangeMatchingStateRq.createNewChangeMatchingStateRq(
                security.getIsin(), MatchingState.AUCTION);
        orderHandler.handleChangeMatchingStateRq(changeStateRq);

        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRqWithStopPrice(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15900, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 15800);

        MatchResult result = security.newOrder(enterOrderRq, testBroker, shareholder, matcher);

        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit);
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(result.trades()).isEmpty();
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION);
    }

    @Test
    void check_if_reopening_works_properly() {  // TODO -> name must be better
        ChangeMatchingStateRq changeStateRq = ChangeMatchingStateRq.createNewChangeMatchingStateRq(
                security.getIsin(), MatchingState.AUCTION);

        orderHandler.handleChangeMatchingStateRq(changeStateRq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.AUCTION));

        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
        brokerRepository.addBroker(testBroker);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 2));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));

        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (100 * 15830));

        ChangeMatchingStateRq changeStateRq2 = ChangeMatchingStateRq.createNewChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION);
        orderHandler.handleChangeMatchingStateRq(changeStateRq2);
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.AUCTION)); //bug

        assertThat(matcher.getLastTradePrice()).isEqualTo(matcher.getReopeningPrice());
        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT + 100 * 15810);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (100 * 15810));
    }
    @Test
    void check_if_reopening_price_changes_after_delete(){
        ChangeMatchingStateRq changeStateRq = ChangeMatchingStateRq.createNewChangeMatchingStateRq(
                security.getIsin(), MatchingState.AUCTION);

        orderHandler.handleChangeMatchingStateRq(changeStateRq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.AUCTION));

        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
        brokerRepository.addBroker(testBroker);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 2));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));

        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (100 * 15830));

        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(4, security.getIsin(), Side.SELL, 7);
        orderHandler.handleDeleteOrder(deleteOrderRq);

        verify(eventPublisher).publish(new OrderDeletedEvent(4,7));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15820, 100));
    }
    @Test
    void check_if_reopening_price_changes_after_update(){
        ChangeMatchingStateRq changeStateRq = ChangeMatchingStateRq.createNewChangeMatchingStateRq(
                security.getIsin(), MatchingState.AUCTION);

        orderHandler.handleChangeMatchingStateRq(changeStateRq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.AUCTION));

        int testBrokerCredit = 20_000_000;
        Broker testBroker = Broker.builder().credit(testBrokerCredit).build();
        brokerRepository.addBroker(testBroker);

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 2,
                LocalDateTime.now(), Side.BUY, 100, 15830, testBroker.getBrokerId(),
                shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 2));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15810, 100));

        assertThat(broker.getCredit()).isEqualTo(MAIN_BROKER_CREDIT);
        assertThat(testBroker.getCredit()).isEqualTo(testBrokerCredit - (100 * 15830));

        EnterOrderRq enterOrderRq1 = EnterOrderRq.createUpdateOrderRq(4, security.getIsin(), 7,
                LocalDateTime.now(), Side.SELL, 100, 15900, broker.getBrokerId(), shareholder.getShareholderId(),  0);
        orderHandler.handleEnterOrder(enterOrderRq1);

        verify(eventPublisher).publish(new OrderUpdatedEvent(4,7));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 15820, 100));
    }
}

//        System.out.println("LTP: " + matcher.getLastTradePrice());
//        System.out.println("RP: " + matcher.getReopeningPrice());
//        System.out.println("Sell: " + security.getOrderBook().getSellQueue());
//        System.out.println("Buy: " + security.getOrderBook().getBuyQueue());