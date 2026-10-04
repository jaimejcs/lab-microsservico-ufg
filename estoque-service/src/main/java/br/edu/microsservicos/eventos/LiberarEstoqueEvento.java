package br.edu.microsservicos.eventos;
public record LiberarEstoqueEvento(Long pedidoId, String correlationId) {}
