package br.edu.microsservicos.pagamento;

import br.edu.microsservicos.eventos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PedidoCriadoConsumerUnitTest {
  @Mock PagamentoRepository pagamentos;
  @Mock RabbitTemplate rabbit;
  @InjectMocks PedidoCriadoConsumer consumer;

  @Test
  void processaPedidoEPublicaResultadoConsistenteComPagamentoPersistido() {
    when(pagamentos.existsByPedidoId(10L)).thenReturn(false);
    ArgumentCaptor<Pagamento> pagamentoCaptor = ArgumentCaptor.forClass(Pagamento.class);
    ArgumentCaptor<PagamentoProcessadoEvento> eventoCaptor = ArgumentCaptor.forClass(PagamentoProcessadoEvento.class);

    consumer.consumir(new PedidoCriadoEvento(10L, 1L, 2, "correlacao-1"));

    verify(pagamentos).save(pagamentoCaptor.capture());
    verify(rabbit).convertAndSend(eq("pagamentos.exchange"), eq("pagamento.processado"), eventoCaptor.capture());
    assertThat(pagamentoCaptor.getValue().pedidoId).isEqualTo(10L);
    assertThat(eventoCaptor.getValue().pedidoId()).isEqualTo(10L);
    assertThat(eventoCaptor.getValue().status()).isEqualTo(pagamentoCaptor.getValue().status.name());
  }

  @Test
  void ignoraEventoDuplicado() {
    when(pagamentos.existsByPedidoId(10L)).thenReturn(true);

    consumer.consumir(new PedidoCriadoEvento(10L, 1L, 2, "correlacao-1"));

    verify(pagamentos, never()).save(any());
    verifyNoInteractions(rabbit);
  }
}
