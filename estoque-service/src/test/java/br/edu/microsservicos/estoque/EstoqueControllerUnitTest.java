package br.edu.microsservicos.estoque;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EstoqueControllerUnitTest {
  @Mock ProdutoRepository produtos;
  @Mock ReservaRepository reservas;
  @InjectMocks EstoqueController controller;

  @Test
  void reservaProdutoQuandoQuantidadeEstaDisponivel() {
    Produto produto = new Produto(1L, "Notebook", 10);
    when(reservas.findByCorrelationId("correlacao-1")).thenReturn(Optional.empty());
    when(produtos.buscarParaAtualizacao(1L)).thenReturn(Optional.of(produto));

    ResponseEntity<Void> resposta = controller.reservar(1L, new QuantidadeRequest(2), "correlacao-1");

    assertThat(resposta.getStatusCode().value()).isEqualTo(200);
    assertThat(produto.quantidade).isEqualTo(8);
    verify(reservas).save(argThat(reserva -> reserva.correlationId.equals("correlacao-1")
        && reserva.produtoId.equals(1L) && reserva.quantidade.equals(2)
        && reserva.status == StatusReserva.RESERVADA));
  }

  @Test
  void naoAlteraEstoqueInsuficiente() {
    Produto produto = new Produto(1L, "Notebook", 1);
    when(reservas.findByCorrelationId(anyString())).thenReturn(Optional.empty());
    when(produtos.buscarParaAtualizacao(1L)).thenReturn(Optional.of(produto));

    assertThatThrownBy(() -> controller.reservar(1L, new QuantidadeRequest(2), "correlacao-2"))
        .isInstanceOf(EstoqueInsuficiente.class);

    assertThat(produto.quantidade).isEqualTo(1);
    verify(reservas, never()).save(any());
  }

  @Test
  void reservaSemCorrelationIdQuandoAChamadaNaoTrazOHeader() {
    Produto produto = new Produto(1L, "Notebook", 10);
    when(produtos.buscarParaAtualizacao(1L)).thenReturn(Optional.of(produto));

    ResponseEntity<Void> resposta = controller.reservar(1L, new QuantidadeRequest(2), null);

    assertThat(resposta.getStatusCode().value()).isEqualTo(200);
    assertThat(produto.quantidade).isEqualTo(8);
    verify(reservas, never()).findByCorrelationId(any());
    verify(reservas, never()).save(any());
  }

  @Test
  void mesmaCorrelacaoNaoReservaDuasVezes() {
    Reserva reserva = new Reserva("correlacao-3", 1L, 2);
    when(reservas.findByCorrelationId("correlacao-3")).thenReturn(Optional.of(reserva));

    ResponseEntity<Void> resposta = controller.reservar(1L, new QuantidadeRequest(2), "correlacao-3");

    assertThat(resposta.getStatusCode().value()).isEqualTo(200);
    verify(produtos, never()).buscarParaAtualizacao(anyLong());
  }
}
