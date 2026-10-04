package br.edu.microsservicos.pedido;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.List;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PedidoController.class)
class PedidoControllerWebTest {
  @Autowired MockMvc mockMvc;
  @MockitoBean PedidoRepository pedidos;
  @MockitoBean RestClient estoque;
  @MockitoBean RabbitTemplate rabbit;

  @Test
  void listaPedidosNoContratoEsperado() throws Exception {
    Pedido pedido = new Pedido(1L, 2, "correlacao-1"); pedido.id = 10L;
    when(pedidos.findAll()).thenReturn(List.of(pedido));

    mockMvc.perform(get("/pedidos"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(10))
        .andExpect(jsonPath("$[0].produtoId").value(1))
        .andExpect(jsonPath("$[0].status").value("AGUARDANDO_PAGAMENTO"));
  }
}
