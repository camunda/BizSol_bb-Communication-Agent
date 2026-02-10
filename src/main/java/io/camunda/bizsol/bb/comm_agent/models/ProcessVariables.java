package io.camunda.bizsol.bb.comm_agent.models;

public class ProcessVariables {
    private ObjectA a;
    private String concatenatedResult;

    public ProcessVariables() {}

    public ProcessVariables(ObjectA a, String concatenatedResult) {
        this.a = a;
        this.concatenatedResult = concatenatedResult;
    }

    public ObjectA getA() {
        return a;
    }

    public void setA(ObjectA a) {
        this.a = a;
    }

    public String getConcatenatedResult() {
        return concatenatedResult;
    }

    public void setConcatenatedResult(String concatenatedResult) {
        this.concatenatedResult = concatenatedResult;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ObjectA a;
        private String concatenatedResult;

        public Builder a(ObjectA a) {
            this.a = a;
            return this;
        }

        public Builder concatenatedResult(String concatenatedResult) {
            this.concatenatedResult = concatenatedResult;
            return this;
        }

        public ProcessVariables build() {
            return new ProcessVariables(a, concatenatedResult);
        }
    }

    @Override
    public String toString() {
        return "ProcessVariables{"
                + "a="
                + a
                + ", concatenatedResult='"
                + concatenatedResult
                + '\''
                + '}';
    }
}
