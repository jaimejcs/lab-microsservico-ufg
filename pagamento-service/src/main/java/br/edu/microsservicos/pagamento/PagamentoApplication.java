package br.edu.microsservicos.pagamento;

import jakarta.persistence.*;
import org.slf4j.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.ThreadLocalRandom;
import br.edu.microsservicos.eventos.*;

@SpringBootApplication
public class PagamentoApplication {
  public static void main(String[] args) { SpringApplication.run(PagamentoApplication.class, args); }
  @Bean TopicExchange pedidosExchange() { return new TopicExchange("pedidos.exchange", true, false); }
  @Bean Queue pedidoCriadoQueue() { return new Queue("pedido.criado", true); }
  @Bean Binding pedidoCriadoBinding() { return BindingBuilder.bind(pedidoCriadoQueue()).to(pedidosExchange()).with("pedido.criado"); }
  @Bean TopicExchange pagamentosExchange() { return new TopicExchange("pagamentos.exchange", true, false); }
  @Bean MessageConverter messageConverter() { return new Jackson2JsonMessageConverter(); }
}

@Entity @Table(name = "pagamento", uniqueConstraints = @UniqueConstraint(columnNames = "pedidoId"))
class Pagamento {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
  @Column(nullable = false) Long pedidoId;
  @Enumerated(EnumType.STRING) StatusPagamento status;
  protected Pagamento() {}
  Pagamento(Long pedidoId, StatusPagamento status) { this.pedidoId = pedidoId; this.status = status; }
}
enum StatusPagamento { APROVADO, REJEITADO }
interface PagamentoRepository extends JpaRepository<Pagamento, Long> { boolean existsByPedidoId(Long pedidoId); }

@Component
class PedidoCriadoConsumer {
  private static final Logger log = LoggerFactory.getLogger(PedidoCriadoConsumer.class);
  private final PagamentoRepository pagamentos; private final RabbitTemplate rabbit;
  PedidoCriadoConsumer(PagamentoRepository pagamentos, RabbitTemplate rabbit) { this.pagamentos = pagamentos; this.rabbit = rabbit; }
  @RabbitListener(queues = "pedido.criado") @Transactional
  public void consumir(PedidoCriadoEvento evento) {
    MDC.put("correlationId", evento.correlationId());
    try {
      if (pagamentos.existsByPedidoId(evento.pedidoId())) { log.info("Evento duplicado ignorado para pedido {}", evento.pedidoId()); return; }
      StatusPagamento status = ThreadLocalRandom.current().nextInt(100) < 80 ? StatusPagamento.APROVADO : StatusPagamento.REJEITADO;
      pagamentos.save(new Pagamento(evento.pedidoId(), status));
      log.info("Pagamento {} para pedido {}", status, evento.pedidoId());
      rabbit.convertAndSend("pagamentos.exchange", "pagamento.processado", new PagamentoProcessadoEvento(evento.pedidoId(), status.name(), evento.correlationId()));
      log.info("Evento pagamento.processado publicado para pedido {}", evento.pedidoId());
    } finally { MDC.remove("correlationId"); }
  }
}
