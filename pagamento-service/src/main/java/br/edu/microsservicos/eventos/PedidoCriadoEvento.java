package br.edu.microsservicos.eventos;
public record PedidoCriadoEvento(Long pedidoId, Long produtoId, Integer quantidade, String correlationId) {}
