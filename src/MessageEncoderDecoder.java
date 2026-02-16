public interface MessageEncoderDecoder<T> {
    T decodeNextByte(byte nextByte);
    byte[] encode(T message);
}