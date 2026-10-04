package br.edu.microsservicos.eventos;
public record PagamentoProcessadoEvento(Long pedidoId, String status, String correlationId) {}
