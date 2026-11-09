package qouteall.imm_ptl.peripheral.riftwoven.environment;

import java.util.ArrayDeque;
import java.util.Deque;

/** Instance-owned, strictly nested view state. The caller must close in a finally/try block. */
public final class ViewStateScope<T> {
    private final Deque<Scope> stack = new ArrayDeque<>();

    public T current() { return stack.isEmpty() ? null : stack.peek().state; }

    public Scope enter(T state) {
        java.util.Objects.requireNonNull(state);
        Scope scope = new Scope(state);
        stack.push(scope);
        return scope;
    }

    public final class Scope implements AutoCloseable {
        private final T state;
        private boolean closed;

        private Scope(T state) { this.state = state; }

        @Override
        public void close() {
            if (closed || stack.peek() != this) {
                throw new IllegalStateException("View scopes must close exactly once, in nesting order");
            }
            stack.pop();
            closed = true;
        }
    }
}
