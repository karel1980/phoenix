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
package org.apache.phoenix.index;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.ipc.BlockingRpcCallback;
import org.apache.hadoop.hbase.ipc.ServerRpcController;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MutationProto;

import com.google.common.collect.Multimap;

import org.apache.phoenix.coprocessor.MetaDataProtocol.MetaDataMutationResult;
import org.apache.phoenix.coprocessor.MetaDataProtocol.MutationCode;
import org.apache.phoenix.hbase.index.table.HTableInterfaceReference;
import org.apache.phoenix.hbase.index.write.KillServerOnFailurePolicy;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.MetaDataResponse;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.MetaDataService;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.UpdateIndexStateRequest;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.protobuf.ProtobufUtil;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.SchemaUtil;

/**
 * 
 * Handler called in the event that index updates cannot be written to their
 * region server. First attempts to disable the index and failing that falls
 * back to the default behavior of killing the region server.
 *
 * TODO: use delegate pattern instead
 * 
 * 
 * @since 2.1
 */
public class PhoenixIndexFailurePolicy extends  KillServerOnFailurePolicy {
    private static final Log LOG = LogFactory.getLog(PhoenixIndexFailurePolicy.class);
    private RegionCoprocessorEnvironment env;

    public PhoenixIndexFailurePolicy() {
    }

    @Override
    public void setup(Stoppable parent, RegionCoprocessorEnvironment env) {
      super.setup(parent, env);
      this.env = env;
    }

    @Override
    public void handleFailure(Multimap<HTableInterfaceReference, Mutation> attempted, Exception cause) throws IOException {
        Set<HTableInterfaceReference> refs = attempted.asMap().keySet();
        List<String> indexTableNames = new ArrayList<String>(1);
        try {
            for (HTableInterfaceReference ref : refs) {
                long minTimeStamp = 0;
                Collection<Mutation> mutations = attempted.get(ref);
                if (mutations != null) {
                  for (Mutation m : mutations) {
                    for (List<Cell> kvs : m.getFamilyCellMap().values()) {
                      for (Cell kv : kvs) {
                        if (minTimeStamp == 0 || (kv.getTimestamp() >=0 && minTimeStamp < kv.getTimestamp())) {
                          minTimeStamp = kv.getTimestamp();
                        }
                      }
                    }
                  }
                }
                
                if(ref.getTableName().startsWith(MetaDataUtil.LOCAL_INDEX_TABLE_PREFIX)) {
                    PhoenixConnection conn = null;
                    try {
                        conn = QueryUtil.getConnection(this.env.getConfiguration()).unwrap(
                                    PhoenixConnection.class);
                        String userTableName = MetaDataUtil.getUserTableName(ref.getTableName());
                        PTable dataTable = PhoenixRuntime.getTable(conn, userTableName);
                        List<PTable> indexes = dataTable.getIndexes();
                        // local index used to get view id from index mutation row key.
                        PTable localIndex = null;
                        Map<ImmutableBytesWritable, String> localIndexNames =
                                new HashMap<ImmutableBytesWritable, String>();
                        for (PTable index : indexes) {
                            if (index.getIndexType() == IndexType.LOCAL
                                    && index.getIndexState() == PIndexState.ACTIVE) {
                                if (localIndex == null) localIndex = index;
                                localIndexNames.put(new ImmutableBytesWritable(MetaDataUtil.getViewIndexIdDataType().toBytes(
                                    index.getViewIndexId())),index.getName().getString());
                            }
                        }
                        if(localIndex == null) continue;
                        
                        IndexMaintainer indexMaintainer = localIndex.getIndexMaintainer(dataTable);
                        HRegionInfo regionInfo = this.env.getRegion().getRegionInfo();
                        int offset =
                                regionInfo.getStartKey().length == 0 ? regionInfo.getEndKey().length
                                        : regionInfo.getStartKey().length;
                        byte[] viewId = null;
                        for (Mutation mutation : mutations) {
                            viewId = indexMaintainer.getViewIndexIdFromIndexRowKey(new ImmutableBytesWritable(mutation.getRow(), offset, mutation.getRow().length - offset));
                            String indexTableName = localIndexNames.get(new ImmutableBytesWritable(viewId)); 
                            if(!indexTableNames.contains(indexTableName)) {
                                indexTableNames.add(indexTableName);
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        throw new IOException(e);
                    } catch (SQLException e) {
                        throw new IOException(e);
                    } finally {
                        if (conn != null) {
                            try {
                                conn.close();
                            } catch (SQLException e) {
                                throw new IOException(e);
                            }
                        }
                    }
                } else {
                    indexTableNames.add(ref.getTableName());
                }

                for (String indexTableName : indexTableNames) {
                    // Disable the index by using the updateIndexState method of MetaDataProtocol end point coprocessor.
                    byte[] indexTableKey = SchemaUtil.getTableKeyFromFullName(indexTableName);
                    HTableInterface systemTable = env.getTable(TableName.valueOf(PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME_BYTES));
                    // Mimic the Put that gets generated by the client on an update of the index state
                    Put put = new Put(indexTableKey);
                    put.add(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES, PhoenixDatabaseMetaData.INDEX_STATE_BYTES, PIndexState.DISABLE.getSerializedBytes());
                    put.add(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES, PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP_BYTES, PDataType.LONG.toBytes(minTimeStamp));
                    final List<Mutation> tableMetadata = Collections.<Mutation>singletonList(put);
                    
                    final Map<byte[], MetaDataResponse> results = 
                            systemTable.coprocessorService(MetaDataService.class, indexTableKey, indexTableKey,
                                new Batch.Call<MetaDataService, MetaDataResponse>() {
                                @Override
                                public MetaDataResponse call(MetaDataService instance) throws IOException {
                                    ServerRpcController controller = new ServerRpcController();
                                    BlockingRpcCallback<MetaDataResponse> rpcCallback =
                                            new BlockingRpcCallback<MetaDataResponse>();
                                    UpdateIndexStateRequest.Builder builder = UpdateIndexStateRequest.newBuilder();
                                    for (Mutation m : tableMetadata) {
                                        MutationProto mp = ProtobufUtil.toProto(m);
                                        builder.addTableMetadataMutations(mp.toByteString());
                                    }
                                    instance.updateIndexState(controller, builder.build(), rpcCallback);
                                    if(controller.getFailedOn() != null) {
                                        throw controller.getFailedOn();
                                    }
                                    return rpcCallback.get();
                                }
                            });
                    if(results.isEmpty()){
                        throw new IOException("Didn't get expected result size");
                    }
                    MetaDataResponse tmpResponse = results.values().iterator().next();
                    MetaDataMutationResult result = MetaDataMutationResult.constructFromProto(tmpResponse);                
                
                    if (result.getMutationCode() != MutationCode.TABLE_ALREADY_EXISTS) {
                        LOG.warn("Attempt to disable index " + indexTableName + " failed with code = " + result.getMutationCode() + ". Will use default failure policy instead.");
                        throw new DoNotRetryIOException("Attemp to disable " + indexTableName + " failed.");
                    }
                    LOG.info("Successfully disabled index " + indexTableName + " due to an exception while writing updates.", cause);
                }
            }
        } catch (Throwable t) {
            LOG.warn("handleFailure failed", t);
            super.handleFailure(attempted, cause);
            throw new DoNotRetryIOException("Attemp to writes to " + indexTableNames + " failed.", cause);
        }
    }

}
