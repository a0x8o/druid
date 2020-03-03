/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.type;

import io.prestosql.metadata.Metadata;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;

import java.util.List;

import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.type.TypeSignature.arrayType;
import static io.prestosql.type.ColorType.COLOR;
import static io.prestosql.util.StructuralTestUtil.arrayBlockOf;

public class TestColorArrayType
        extends AbstractTestType
{
    public TestColorArrayType()
    {
        this(createTestMetadataManager());
    }

    private TestColorArrayType(Metadata metadata)
    {
        super(metadata.getType(arrayType(COLOR.getTypeSignature())), List.class, createTestBlock(metadata.getType(arrayType(COLOR.getTypeSignature()))));
    }

    public static Block createTestBlock(Type arrayType)
    {
        BlockBuilder blockBuilder = arrayType.createBlockBuilder(null, 4);
        arrayType.writeObject(blockBuilder, arrayBlockOf(COLOR, 1, 2));
        arrayType.writeObject(blockBuilder, arrayBlockOf(COLOR, 1, 2, 3));
        arrayType.writeObject(blockBuilder, arrayBlockOf(COLOR, 1, 2, 3));
        arrayType.writeObject(blockBuilder, arrayBlockOf(COLOR, 100, 200, 300));
        return blockBuilder.build();
    }

    @Override
    protected Object getGreaterValue(Object value)
    {
        throw new UnsupportedOperationException();
    }
}
