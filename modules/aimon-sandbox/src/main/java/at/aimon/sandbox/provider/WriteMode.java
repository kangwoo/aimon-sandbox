package at.aimon.sandbox.provider;

/** How {@link SandboxFiles#write} treats an existing file. */
public enum WriteMode {

    /** Replace an existing file; create missing parent directories. */
    CREATE_OR_REPLACE,

    /** Fail with {@code FileAlreadyExistsException} when the file exists; create missing parent directories. */
    CREATE_NEW
}
