public interface MessagingProtocol<T> {
    public T process(T msg);
    public boolean shouldTerminate();
}