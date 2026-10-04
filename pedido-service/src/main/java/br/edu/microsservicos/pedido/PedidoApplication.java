package br.edu.microsservicos.pedido;

import jakarta.persistence.*;
import org.slf4j.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.stereotype.Component;
import br.edu.microsservicos.eventos.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootApplication
public class PedidoApplication {
  public static void main(String[] args) { SpringApplication.run(PedidoApplication.class, args); }
  @Bean RestClient estoqueClient(@Value("${estoque-service.url}") String url) { return RestClient.builder().baseUrl(url).build(); }
  @Bean TopicExchange pedidosExchange() { return new TopicExchange("pedidos.exchange", true, false); }
  @Bean Queue pedidoCriadoQueue() { return new Queue("pedido.criado", true); }
  @Bean Binding pedidoCriadoBinding() { return BindingBuilder.bind(pedidoCriadoQueue()).to(pedidosExchange()).with("pedido.criado"); }
  @Bean TopicExchange pagamentosExchange() { return new TopicExchange("pagamentos.exchange", true, false); }
  @Bean Queue pagamentoProcessadoQueue() { return new Queue("pagamento.processado", true); }
  @Bean Binding pagamentoProcessadoBinding() { return BindingBuilder.bind(pagamentoProcessadoQueue()).to(pagamentosExchange()).with("pagamento.processado"); }
  @Bean TopicExchange estoqueExchange() { return new TopicExchange("estoque.exchange", true, false); }
  @Bean MessageConverter messageConverter() { return new Jackson2JsonMessageConverter(); }
}

@Entity @Table(name = "pedido")
class Pedido {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
  Long produtoId; Integer quantidade;
  @Enumerated(EnumType.STRING) StatusPedido status;
  @Column(unique = true, nullable = false) String correlationId;
  protected Pedido() {}
  Pedido(Long produtoId, Integer quantidade, String correlationId) { this.produtoId = produtoId; this.quantidade = quantidade; this.correlationId = correlationId; this.status = StatusPedido.AGUARDANDO_PAGAMENTO; }
}
enum StatusPedido { AGUARDANDO_PAGAMENTO, PAGO, REJEITADO }
interface PedidoRepository extends JpaRepository<Pedido, Long> {}
record CriarPedidoRequest(Long produtoId, Integer quantidade) {}
record PedidoResponse(Long id, Long produtoId, Integer quantidade, StatusPedido status, String correlationId) { static PedidoResponse de(Pedido p) { return new PedidoResponse(p.id, p.produtoId, p.quantidade, p.status, p.correlationId); } }
record ErroResponse(String mensagem) {}

@RestController @RequestMapping("/pedidos")
class PedidoController {
  private static final Logger log = LoggerFactory.getLogger(PedidoController.class);
  private final PedidoRepository pedidos; private final RestClient estoque; private final RabbitTemplate rabbit;
  PedidoController(PedidoRepository pedidos, RestClient estoque, RabbitTemplate rabbit) { this.pedidos = pedidos; this.estoque = estoque; this.rabbit = rabbit; }
  @GetMapping List<PedidoResponse> listar() { return pedidos.findAll().stream().map(PedidoResponse::de).toList(); }
  @GetMapping("/{id}") PedidoResponse buscar(@PathVariable Long id) { return PedidoResponse.de(pedidos.findById(id).orElseThrow(PedidoNaoEncontrado::new)); }

  @PostMapping @Transactional
  ResponseEntity<PedidoResponse> criar(@RequestBody CriarPedidoRequest request) {
    if (request.produtoId() == null || request.quantidade() == null || request.quantidade() <= 0) throw new IllegalArgumentException("produtoId e quantidade positiva são obrigatórios");
    String correlationId = UUID.randomUUID().toString(); MDC.put("correlationId", correlationId);
    try {
      reservarEstoque(request, correlationId);
      Pedido pedido = pedidos.save(new Pedido(request.produtoId(), request.quantidade(), correlationId));
      log.info("Pedido {} criado", pedido.id);
      rabbit.convertAndSend("pedidos.exchange", "pedido.criado", new PedidoCriadoEvento(pedido.id, pedido.produtoId, pedido.quantidade, correlationId));
      log.info("Evento pedido.criado publicado para pedido {}", pedido.id);
      return ResponseEntity.status(HttpStatus.CREATED).body(PedidoResponse.de(pedido));
    } finally { MDC.remove("correlationId"); }
  }

  private void reservarEstoque(CriarPedidoRequest request, String correlationId) {
    try {
      estoque.put().uri("/produtos/{id}/reservar", request.produtoId())
          .header("X-Correlation-Id", correlationId).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("quantidade", request.quantidade())).retrieve().toBodilessEntity();
    } catch (org.springframework.web.client.RestClientResponseException e) { throw new EstoqueIndisponivelOuInvalido(e.getStatusCode(), e.getResponseBodyAsString()); }
    catch (org.springframework.web.client.RestClientException e) { throw new EstoqueIndisponivelOuInvalido(HttpStatus.SERVICE_UNAVAILABLE, "Estoque Service indisponível"); }
  }
  @ExceptionHandler(PedidoNaoEncontrado.class) ResponseEntity<ErroResponse> naoEncontrado() { return ResponseEntity.status(404).body(new ErroResponse("Pedido inexistente")); }
  @ExceptionHandler(EstoqueIndisponivelOuInvalido.class) ResponseEntity<ErroResponse> erroEstoque(EstoqueIndisponivelOuInvalido e) { return ResponseEntity.status(e.status).body(new ErroResponse(e.getMessage())); }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ErroResponse> invalido(IllegalArgumentException e) { return ResponseEntity.badRequest().body(new ErroResponse(e.getMessage())); }
}

@Component
class PagamentoProcessadoConsumer {
  private static final Logger log = LoggerFactory.getLogger(PagamentoProcessadoConsumer.class);
  private final PedidoRepository pedidos; private final RabbitTemplate rabbit;
  PagamentoProcessadoConsumer(PedidoRepository pedidos, RabbitTemplate rabbit) { this.pedidos = pedidos; this.rabbit = rabbit; }
  @RabbitListener(queues = "pagamento.processado") @Transactional
  public void consumir(PagamentoProcessadoEvento evento) {
    MDC.put("correlationId", evento.correlationId());
    try {
      Pedido pedido = pedidos.findById(evento.pedidoId()).orElseThrow(PedidoNaoEncontrado::new);
      StatusPedido novoStatus = "APROVADO".equals(evento.status()) ? StatusPedido.PAGO : StatusPedido.REJEITADO;
      if (pedido.status != StatusPedido.AGUARDANDO_PAGAMENTO) return;
      pedido.status = novoStatus; log.info("Pedido {} atualizado para {}", pedido.id, novoStatus);
      if (novoStatus == StatusPedido.REJEITADO) {
        rabbit.convertAndSend("estoque.exchange", "estoque.liberar", new LiberarEstoqueEvento(pedido.id, pedido.correlationId));
        log.info("Evento de liberação de estoque publicado para pedido {}", pedido.id);
      }
    } finally { MDC.remove("correlationId"); }
  }
}
class PedidoNaoEncontrado extends RuntimeException { PedidoNaoEncontrado() { super("Pedido inexistente"); } }
class EstoqueIndisponivelOuInvalido extends RuntimeException { final HttpStatusCode status; EstoqueIndisponivelOuInvalido(HttpStatusCode status, String message) { super(message); this.status = status; } }
