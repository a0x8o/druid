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
package com.facebook.presto.spi;

public class PageSinkProperties
{
    private static final PageSinkProperties DEFAULT_PAGE_SINK_PROPERTIES = PageSinkProperties.builder().build();

    private final boolean partitionCommitRequired;

    private PageSinkProperties(boolean partitionCommitRequired)
    {
        this.partitionCommitRequired = partitionCommitRequired;
    }

    public static PageSinkProperties defaultProperties()
    {
        return DEFAULT_PAGE_SINK_PROPERTIES;
    }

    public boolean isPartitionCommitRequired()
    {
        return partitionCommitRequired;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static final class Builder
    {
        private boolean partitionCommitRequired;

        public Builder setPartitionCommitRequired(boolean partitionCommitRequired)
        {
            this.partitionCommitRequired = partitionCommitRequired;
            return this;
        }

        public PageSinkProperties build()
        {
            return new PageSinkProperties(partitionCommitRequired);
        }
    }
}
