package br.edu.microsservicos.estoque;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import java.util.List;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EstoqueController.class)
class EstoqueControllerWebTest {
  @Autowired MockMvc mockMvc;
  @MockitoBean ProdutoRepository produtos;
  @MockitoBean ReservaRepository reservas;

  @Test
  void listaProdutosNoContratoEsperado() throws Exception {
    when(produtos.findAll()).thenReturn(List.of(new Produto(1L, "Notebook", 10)));

    mockMvc.perform(get("/produtos"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].nome").value("Notebook"))
        .andExpect(jsonPath("$[0].quantidade").value(10));
  }

  @Test
  void reservaSemHeaderDeCorrelacao() throws Exception {
    when(produtos.buscarParaAtualizacao(1L)).thenReturn(Optional.of(new Produto(1L, "Notebook", 10)));

    mockMvc.perform(put("/produtos/1/reservar")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantidade\":2}"))
        .andExpect(status().isOk());
  }
}
