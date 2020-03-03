/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.clients.admin;

import java.util.HashMap;
import java.util.Locale;

/**
 * Represents an operation which an ACL grants or denies permission to perform.
 */
public enum AclOperation {
    /**
     * Represents any AclOperation which this client cannot understand, perhaps because this
     * client is too old.
     */
    UNKNOWN((byte) 0),

    /**
     * In a filter, matches any AclOperation.
     */
    ANY((byte) 1),

    /**
     * ALL operation.
     */
    ALL((byte) 2),

    /**
     * READ operation.
     */
    READ((byte) 3),

    /**
     * WRITE operation.
     */
    WRITE((byte) 4),

    /**
     * CREATE operation.
     */
    CREATE((byte) 5),

    /**
     * DELETE operation.
     */
    DELETE((byte) 6),

    /**
     * ALTER operation.
     */
    ALTER((byte) 7),

    /**
     * DESCRIBE operation.
     */
    DESCRIBE((byte) 8),

    /**
     * CLUSTER_ACTION operation.
     */
    CLUSTER_ACTION((byte) 9),

    /**
     * DESCRIBE_CONFIGS operation.
     */
    DESCRIBE_CONFIGS((byte) 10),

    /**
     * ALTER_CONFIGS operation.
     */
    ALTER_CONFIGS((byte) 11),

    /**
     * IDEMPOTENT_WRITE operation.
     */
    IDEMPOTENT_WRITE((byte) 12);

    private final static HashMap<Byte, AclOperation> CODE_TO_VALUE = new HashMap<>();

    static {
        for (AclOperation operation : AclOperation.values()) {
            CODE_TO_VALUE.put(operation.code, operation);
        }
    }

    /**
     * Parse the given string as an ACL operation.
     *
     * @param str    The string to parse.
     *
     * @return       The AclOperation, or UNKNOWN if the string could not be matched.
     */
    public static AclOperation fromString(String str) throws IllegalArgumentException {
        try {
            return AclOperation.valueOf(str.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public static AclOperation fromCode(byte code) {
        AclOperation operation = CODE_TO_VALUE.get(code);
        if (operation == null) {
            return UNKNOWN;
        }
        return operation;
    }

    private final byte code;

    AclOperation(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public boolean unknown() {
        return this == UNKNOWN;
    }
}
