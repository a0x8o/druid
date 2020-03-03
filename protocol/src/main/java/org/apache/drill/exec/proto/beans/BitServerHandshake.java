/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Generated by http://code.google.com/p/protostuff/ ... DO NOT EDIT!
// Generated from protobuf

package org.apache.drill.exec.proto.beans;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.List;

import com.dyuproject.protostuff.GraphIOUtil;
import com.dyuproject.protostuff.Input;
import com.dyuproject.protostuff.Message;
import com.dyuproject.protostuff.Output;
import com.dyuproject.protostuff.Schema;

public final class BitServerHandshake implements Externalizable, Message<BitServerHandshake>, Schema<BitServerHandshake>
{

    public static Schema<BitServerHandshake> getSchema()
    {
        return DEFAULT_INSTANCE;
    }

    public static BitServerHandshake getDefaultInstance()
    {
        return DEFAULT_INSTANCE;
    }

    static final BitServerHandshake DEFAULT_INSTANCE = new BitServerHandshake();

    
    private int rpcVersion;
    private List<String> authenticationMechanisms;

    public BitServerHandshake()
    {
        
    }

    // getters and setters

    // rpcVersion

    public int getRpcVersion()
    {
        return rpcVersion;
    }

    public BitServerHandshake setRpcVersion(int rpcVersion)
    {
        this.rpcVersion = rpcVersion;
        return this;
    }

    // authenticationMechanisms

    public List<String> getAuthenticationMechanismsList()
    {
        return authenticationMechanisms;
    }

    public BitServerHandshake setAuthenticationMechanismsList(List<String> authenticationMechanisms)
    {
        this.authenticationMechanisms = authenticationMechanisms;
        return this;
    }

    // java serialization

    public void readExternal(ObjectInput in) throws IOException
    {
        GraphIOUtil.mergeDelimitedFrom(in, this, this);
    }

    public void writeExternal(ObjectOutput out) throws IOException
    {
        GraphIOUtil.writeDelimitedTo(out, this, this);
    }

    // message method

    public Schema<BitServerHandshake> cachedSchema()
    {
        return DEFAULT_INSTANCE;
    }

    // schema methods

    public BitServerHandshake newMessage()
    {
        return new BitServerHandshake();
    }

    public Class<BitServerHandshake> typeClass()
    {
        return BitServerHandshake.class;
    }

    public String messageName()
    {
        return BitServerHandshake.class.getSimpleName();
    }

    public String messageFullName()
    {
        return BitServerHandshake.class.getName();
    }

    public boolean isInitialized(BitServerHandshake message)
    {
        return true;
    }

    public void mergeFrom(Input input, BitServerHandshake message) throws IOException
    {
        for(int number = input.readFieldNumber(this);; number = input.readFieldNumber(this))
        {
            switch(number)
            {
                case 0:
                    return;
                case 1:
                    message.rpcVersion = input.readInt32();
                    break;
                case 2:
                    if(message.authenticationMechanisms == null)
                        message.authenticationMechanisms = new ArrayList<String>();
                    message.authenticationMechanisms.add(input.readString());
                    break;
                default:
                    input.handleUnknownField(number, this);
            }   
        }
    }


    public void writeTo(Output output, BitServerHandshake message) throws IOException
    {
        if(message.rpcVersion != 0)
            output.writeInt32(1, message.rpcVersion, false);

        if(message.authenticationMechanisms != null)
        {
            for(String authenticationMechanisms : message.authenticationMechanisms)
            {
                if(authenticationMechanisms != null)
                    output.writeString(2, authenticationMechanisms, true);
            }
        }
    }

    public String getFieldName(int number)
    {
        switch(number)
        {
            case 1: return "rpcVersion";
            case 2: return "authenticationMechanisms";
            default: return null;
        }
    }

    public int getFieldNumber(String name)
    {
        final Integer number = __fieldMap.get(name);
        return number == null ? 0 : number.intValue();
    }

    private static final java.util.HashMap<String,Integer> __fieldMap = new java.util.HashMap<String,Integer>();
    static
    {
        __fieldMap.put("rpcVersion", 1);
        __fieldMap.put("authenticationMechanisms", 2);
    }
    
}
