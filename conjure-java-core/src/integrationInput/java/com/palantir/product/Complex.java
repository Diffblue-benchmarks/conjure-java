package com.palantir.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Generated;

@JsonDeserialize(builder = Complex.Builder.class)
@Generated("com.palantir.conjure.java.types.BeanGenerator")
public final class Complex {
    private final String string;

    private final int int_;

    private volatile int memoizedHashCode;

    private Complex(String string, int int_) {
        validateFields(string);
        this.string = string;
        this.int_ = int_;
    }

    @JsonProperty("string")
    public String getString() {
        return this.string;
    }

    @JsonProperty("int")
    public int getInt() {
        return this.int_;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Complex && equalTo((Complex) other));
    }

    private boolean equalTo(Complex other) {
        return this.string.equals(other.string) && this.int_ == other.int_;
    }

    @Override
    public int hashCode() {
        if (memoizedHashCode == 0) {
            memoizedHashCode = Objects.hash(string, int_);
        }
        return memoizedHashCode;
    }

    @Override
    public String toString() {
        return new StringBuilder("Complex")
                .append('{')
                .append("string")
                .append(": ")
                .append(string)
                .append(", ")
                .append("int")
                .append(": ")
                .append(int_)
                .append('}')
                .toString();
    }

    public static Complex of(String string, int int_) {
        return builder().string(string).int_(int_).build();
    }

    private static void validateFields(String string) {
        List<String> missingFields = null;
        missingFields = addFieldIfMissing(missingFields, string, "string");
        if (missingFields != null) {
            throw new SafeIllegalArgumentException(
                    "Some required fields have not been set",
                    SafeArg.of("missingFields", missingFields));
        }
    }

    private static List<String> addFieldIfMissing(
            List<String> prev, Object fieldValue, String fieldName) {
        List<String> missingFields = prev;
        if (fieldValue == null) {
            if (missingFields == null) {
                missingFields = new ArrayList<>(1);
            }
            missingFields.add(fieldName);
        }
        return missingFields;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Generated("com.palantir.conjure.java.types.BeanBuilderGenerator")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Builder {
        private String string;

        private int int_;

        private boolean _int_Initialized = false;

        private Builder() {}

        public Builder from(Complex other) {
            string(other.getString());
            int_(other.getInt());
            return this;
        }

        @JsonSetter("string")
        public Builder string(String string) {
            this.string = Objects.requireNonNull(string, "string cannot be null");
            return this;
        }

        @JsonSetter("int")
        public Builder int_(int int_) {
            this.int_ = int_;
            this._int_Initialized = true;
            return this;
        }

        private void validatePrimitiveFieldsHaveBeenInitialized() {
            List<String> missingFields = null;
            missingFields = addFieldIfMissing(missingFields, _int_Initialized, "int");
            if (missingFields != null) {
                throw new SafeIllegalArgumentException(
                        "Some required fields have not been set",
                        SafeArg.of("missingFields", missingFields));
            }
        }

        private static List<String> addFieldIfMissing(
                List<String> prev, boolean initialized, String fieldName) {
            List<String> missingFields = prev;
            if (!initialized) {
                if (missingFields == null) {
                    missingFields = new ArrayList<>(1);
                }
                missingFields.add(fieldName);
            }
            return missingFields;
        }

        public Complex build() {
            validatePrimitiveFieldsHaveBeenInitialized();
            return new Complex(string, int_);
        }
    }
}
