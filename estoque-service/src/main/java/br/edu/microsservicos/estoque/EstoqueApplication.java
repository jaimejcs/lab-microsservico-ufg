package br.edu.microsservicos.estoque;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.slf4j.MDC;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;
import br.edu.microsservicos.eventos.LiberarEstoqueEvento;

@SpringBootApplication
public class EstoqueApplication {
  public static void main(String[] args) { SpringApplication.run(EstoqueApplication.class, args); }

  @Bean CommandLineRunner dadosIniciais(ProdutoRepository produtos) {
    return args -> { if (produtos.count() == 0) {
      produtos.saveAll(List.of(new Produto(1L, "Notebook", 10), new Produto(2L, "Mouse", 50), new Produto(3L, "Teclado", 20)));
    }};
  }
  @Bean TopicExchange estoqueExchange() { return new TopicExchange("estoque.exchange", true, false); }
  @Bean Queue estoqueLiberarQueue() { return new Queue("estoque.liberar", true); }
  @Bean Binding estoqueLiberarBinding() { return BindingBuilder.bind(estoqueLiberarQueue()).to(estoqueExchange()).with("estoque.liberar"); }
  @Bean MessageConverter messageConverter() { return new Jackson2JsonMessageConverter(); }
}

@Entity @Table(name = "produto")
class Produto {
  @Id Long id; String nome; Integer quantidade;
  protected Produto() {}
  Produto(Long id, String nome, Integer quantidade) { this.id = id; this.nome = nome; this.quantidade = quantidade; }
}

@Entity @Table(name = "reserva", uniqueConstraints = @UniqueConstraint(columnNames = "correlationId"))
class Reserva {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
  @Column(nullable = false) String correlationId;
  @Column(nullable = false) Long produtoId;
  @Column(nullable = false) Integer quantidade;
  @Enumerated(EnumType.STRING) StatusReserva status;
  protected Reserva() {}
  Reserva(String correlationId, Long produtoId, Integer quantidade) { this.correlationId = correlationId; this.produtoId = produtoId; this.quantidade = quantidade; this.status = StatusReserva.RESERVADA; }
}
enum StatusReserva { RESERVADA, LIBERADA }

interface ProdutoRepository extends JpaRepository<Produto, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from Produto p where p.id = :id") Optional<Produto> buscarParaAtualizacao(Long id);
}
interface ReservaRepository extends JpaRepository<Reserva, Long> { Optional<Reserva> findByCorrelationId(String correlationId); }

record QuantidadeRequest(Integer quantidade) {}
record ProdutoResponse(Long id, String nome, Integer quantidade) { static ProdutoResponse de(Produto p) { return new ProdutoResponse(p.id, p.nome, p.quantidade); } }
record ErroResponse(String mensagem) {}

@RestController @RequestMapping("/produtos")
@CrossOrigin(origins = "http://localhost:8080")
class EstoqueController {
  private final ProdutoRepository produtos; private final ReservaRepository reservas;
  EstoqueController(ProdutoRepository produtos, ReservaRepository reservas) { this.produtos = produtos; this.reservas = reservas; }

  @GetMapping List<ProdutoResponse> listar() { return produtos.findAll().stream().map(ProdutoResponse::de).toList(); }
  @GetMapping("/{id}") ProdutoResponse buscar(@PathVariable Long id) { return ProdutoResponse.de(produtos.findById(id).orElseThrow(ProdutoNaoEncontrado::new)); }

  @PutMapping("/{id}/reservar") @Transactional
  ResponseEntity<Void> reservar(@PathVariable Long id, @RequestBody QuantidadeRequest request, @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
    boolean correlacionado = correlationId != null && !correlationId.isBlank();
    if (correlacionado) MDC.put("correlationId", correlationId);
    try {
      if (request.quantidade() == null || request.quantidade() <= 0) throw new IllegalArgumentException("Quantidade deve ser positiva");
      if (correlacionado && reservas.findByCorrelationId(correlationId).isPresent()) return ResponseEntity.ok().build();
      var produto = produtos.buscarParaAtualizacao(id).orElseThrow(ProdutoNaoEncontrado::new);
      if (produto.quantidade < request.quantidade()) throw new EstoqueInsuficiente();
      produto.quantidade -= request.quantidade();
      if (correlacionado) reservas.save(new Reserva(correlationId, id, request.quantidade()));
      org.slf4j.LoggerFactory.getLogger(getClass()).info("Produto {} reservado: {} unidade(s)", id, request.quantidade());
      return ResponseEntity.ok().build();
    } finally { MDC.remove("correlationId"); }
  }

  @PutMapping("/reservas/{correlationId}/liberar") @Transactional
  ResponseEntity<Void> liberar(@PathVariable String correlationId) {
    var reserva = reservas.findByCorrelationId(correlationId).orElseThrow(ReservaNaoEncontrada::new);
    MDC.put("correlationId", correlationId);
    try {
      if (reserva.status == StatusReserva.LIBERADA) return ResponseEntity.ok().build();
      var produto = produtos.buscarParaAtualizacao(reserva.produtoId).orElseThrow(ProdutoNaoEncontrado::new);
      produto.quantidade += reserva.quantidade; reserva.status = StatusReserva.LIBERADA;
      org.slf4j.LoggerFactory.getLogger(getClass()).info("Reserva do produto {} liberada: {} unidade(s)", produto.id, reserva.quantidade);
      return ResponseEntity.ok().build();
    } finally { MDC.remove("correlationId"); }
  }

  @ExceptionHandler(ProdutoNaoEncontrado.class) ResponseEntity<ErroResponse> produtoNaoExiste() { return ResponseEntity.status(404).body(new ErroResponse("Produto inexistente")); }
  @ExceptionHandler(EstoqueInsuficiente.class) ResponseEntity<ErroResponse> estoqueInsuficiente() { return ResponseEntity.status(409).body(new ErroResponse("Estoque insuficiente")); }
  @ExceptionHandler({ReservaNaoEncontrada.class, IllegalArgumentException.class}) ResponseEntity<ErroResponse> requisicaoInvalida(Exception e) { return ResponseEntity.badRequest().body(new ErroResponse(e.getMessage())); }
}
@org.springframework.stereotype.Component
class LiberarEstoqueConsumer {
  private final EstoqueController controller;
  LiberarEstoqueConsumer(EstoqueController controller) { this.controller = controller; }
  @RabbitListener(queues = "estoque.liberar")
  void consumir(LiberarEstoqueEvento evento) { controller.liberar(evento.correlationId()); }
}
class ProdutoNaoEncontrado extends RuntimeException { ProdutoNaoEncontrado() { super("Produto inexistente"); } }
class EstoqueInsuficiente extends RuntimeException { EstoqueInsuficiente() { super("Estoque insuficiente"); } }
class ReservaNaoEncontrada extends RuntimeException { ReservaNaoEncontrada() { super("Reserva inexistente"); } }
