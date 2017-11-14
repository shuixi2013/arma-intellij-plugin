package com.kaylerrenslow.armaplugin.lang.sqf.syntax;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;

/**
 * A wrapper {@link InputStream} for retrieving syntax XML for a command.
 *
 * @author Kayler
 * @since 11/13/2017
 */
public class CommandXMLInputStream extends InputStream {
	@NotNull
	private final InputStream is;
	@NotNull
	private final String commandName;

	public CommandXMLInputStream(@NotNull InputStream is, @NotNull String commandName) {
		this.is = is;
		this.commandName = commandName;
	}

	@NotNull
	public String getCommandName() {
		return commandName;
	}

	@Override
	public int read() throws IOException {
		return is.read();
	}

	@Override
	public int read(@NotNull byte[] b) throws IOException {
		return is.read(b);
	}

	@Override
	public int read(@NotNull byte[] b, int off, int len) throws IOException {
		return is.read(b, off, len);
	}

	@Override
	public long skip(long n) throws IOException {
		return is.skip(n);
	}

	@Override
	public int available() throws IOException {
		return is.available();
	}

	@Override
	public void close() throws IOException {
		is.close();
	}

	@Override
	public synchronized void mark(int readlimit) {
		is.mark(readlimit);
	}

	@Override
	public synchronized void reset() throws IOException {
		is.reset();
	}

	@Override
	public boolean markSupported() {
		return is.markSupported();
	}
}
