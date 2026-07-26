package me.rama.ex;

/**
 * Excepción lanzada cuando se intenta remover un voto de un meme
 * que actualmente tiene 0 votos, lo cual es una operación inválida.
 */
public class InvalidVoteException extends Exception {

    public InvalidVoteException(String message) {
        super(message);
    }

}
