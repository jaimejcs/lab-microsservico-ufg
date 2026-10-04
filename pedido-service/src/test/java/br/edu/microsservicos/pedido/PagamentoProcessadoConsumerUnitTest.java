package br.edu.microsservicos.pedido;

import br.edu.microsservicos.eventos.PagamentoProcessadoEvento;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PagamentoProcessadoConsumerUnitTest {
  @Mock PedidoRepository pedidos;
  @Mock RabbitTemplate rabbit;
  @InjectMocks PagamentoProcessadoConsumer consumer;

  @Test
  void marcaPedidoComoPagoQuandoPagamentoAprovado() {
    Pedido pedido = new Pedido(1L, 2, "correlacao-1"); pedido.id = 10L;
    when(pedidos.findById(10L)).thenReturn(Optional.of(pedido));

    consumer.consumir(new PagamentoProcessadoEvento(10L, "APROVADO", "correlacao-1"));

    assertThat(pedido.status).isEqualTo(StatusPedido.PAGO);
    verify(rabbit, never()).convertAndSend(eq("estoque.exchange"), anyString(), any(Object.class));
  }

  @Test
  void rejeicaoAtualizaPedidoEPublicaCompensacao() {
    Pedido pedido = new Pedido(1L, 2, "correlacao-2"); pedido.id = 11L;
    when(pedidos.findById(11L)).thenReturn(Optional.of(pedido));

    consumer.consumir(new PagamentoProcessadoEvento(11L, "REJEITADO", "correlacao-2"));

    assertThat(pedido.status).isEqualTo(StatusPedido.REJEITADO);
    verify(rabbit).convertAndSend(eq("estoque.exchange"), eq("estoque.liberar"), any(Object.class));
  }
}
