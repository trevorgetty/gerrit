package com.google.gerrit.server.exceptions;

public final class ReplicationConfigException extends RuntimeException{

	public ReplicationConfigException(final String message){
		super(message);
	}


	public ReplicationConfigException(final Throwable throwable){
		super(throwable);
	}

	public ReplicationConfigException(final String message, final Throwable throwable){
		super(message, throwable);
	}
}
