/*
 * Copyright 2017 - 2019 Volker Berlin (i-net software)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.inetsoftware.jwebassembly.binary;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import de.inetsoftware.classparser.Member;
import de.inetsoftware.jwebassembly.JWebAssembly;
import de.inetsoftware.jwebassembly.module.FunctionName;
import de.inetsoftware.jwebassembly.module.ModuleWriter;
import de.inetsoftware.jwebassembly.module.ValueTypeConvertion;
import de.inetsoftware.jwebassembly.wasm.ArrayOperator;
import de.inetsoftware.jwebassembly.wasm.NamedStorageType;
import de.inetsoftware.jwebassembly.wasm.NumericOperator;
import de.inetsoftware.jwebassembly.wasm.AnyType;
import de.inetsoftware.jwebassembly.wasm.StructOperator;
import de.inetsoftware.jwebassembly.wasm.ValueType;
import de.inetsoftware.jwebassembly.wasm.VariableOperator;
import de.inetsoftware.jwebassembly.wasm.WasmBlockOperator;

/**
 * Module Writer for binary format. http://webassembly.org/docs/binary-encoding/
 * 
 * @author Volker Berlin
 */
public class BinaryModuleWriter extends ModuleWriter implements InstructionOpcodes {

    private static final byte[]         WASM_BINARY_MAGIC   = { 0, 'a', 's', 'm' };

    private static final int            WASM_BINARY_VERSION = 1;

    private WasmOutputStream            wasm;

    private final boolean               debugNames;

    private WasmOutputStream            codeStream          = new WasmOutputStream();

    private List<TypeEntry>             functionTypes       = new ArrayList<>();

    private Map<String, Function>       functions           = new LinkedHashMap<>();

    private List<AnyType>               locals              = new ArrayList<>();

    private Map<String, Global>         globals             = new LinkedHashMap<>();

    private Map<String, String>         exports             = new LinkedHashMap<>();

    private Map<String, ImportFunction> imports             = new LinkedHashMap<>();

    private Function                    function;

    private FunctionTypeEntry           functionType;

    private int                         exceptionSignatureIndex       = -1;

    /**
     * Create new instance.
     * 
     * @param output
     *            the target for the module data.
     * @param properties
     *            compiler properties
     * @throws IOException
     *             if any I/O error occur
     */
    public BinaryModuleWriter( OutputStream output, HashMap<String, String> properties ) throws IOException {
        wasm = new WasmOutputStream( output );
        debugNames = Boolean.parseBoolean( properties.get( JWebAssembly.DEBUG_NAMES ) );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        wasm.write( WASM_BINARY_MAGIC );
        wasm.writeInt32( WASM_BINARY_VERSION );

//        // Section 42, enable GcFeatureOptIn for SpiderMonkey https://github.com/lars-t-hansen/moz-gc-experiments/blob/master/version1.md
//        wasm.writeVaruint32( 42 );
//        wasm.writeVaruint32( 1 );
//        wasm.write( 2 ); // version of GcFeatureOptIn
//        // End Section 42

        writeSection( SectionType.Type, functionTypes );
        writeSection( SectionType.Import, imports.values() );
        writeSection( SectionType.Function, functions.values() );
        writeSection( SectionType.Global, globals.values() );
        writeEventSection();
        writeExportSection();
        writeCodeSection();
        writeDebugNames();
        writeProducersSection();

        wasm.close();
    }

    /**
     * Write a section with list format to the output.
     * 
     * @param type
     *            the type of the section
     * @param entries
     *            the entries of the section
     * @throws IOException
     *             if any I/O error occur
     */
    private void writeSection( SectionType type, Collection<? extends SectionEntry> entries ) throws IOException {
        int count = entries.size();
        if( count > 0 ) {
            WasmOutputStream stream = new WasmOutputStream();
            stream.writeVaruint32( count );
            for( SectionEntry entry : entries ) {
                entry.writeSectionEntry( stream );
            }
            wasm.writeSection( type, stream );
        }
    }

    /**
     * Write the event section if needed.
     * 
     * @throws IOException
     *             if any I/O error occur
     */
    private void writeEventSection() throws IOException {
        if( exceptionSignatureIndex >= 0 ) {
            WasmOutputStream stream = new WasmOutputStream();
            stream.writeVaruint32( 1 );

            // event declaration
            stream.writeVaruint32( 0 ); // event type: exception = 0
            stream.writeVaruint32( exceptionSignatureIndex );

            wasm.writeSection( SectionType.Event, stream );
        }
    }

    /**
     * Write the export section to the output. This section contains a mapping from the external index to the type signature index.
     * 
     * @throws IOException
     *             if any I/O error occur
     */
    private void writeExportSection() throws IOException {
        int count = exports.size();
        if( count > 0 ) {
            WasmOutputStream stream = new WasmOutputStream();
            stream.writeVaruint32( count );
            for( Map.Entry<String,String> entry : exports.entrySet() ) {
                String exportName = entry.getKey();
                byte[] bytes = exportName.getBytes( StandardCharsets.UTF_8 );
                stream.writeVaruint32( bytes.length );
                stream.write( bytes );
                stream.writeVaruint32( ExternalKind.Function.ordinal() );
                int id = functions.get( entry.getValue() ).id;
                stream.writeVaruint32( id );
            }
            wasm.writeSection( SectionType.Export, stream );
        }
    }

    /**
     * Write the code section to the output. This section contains the byte code.
     * 
     * @throws IOException
     *             if any I/O error occur
     */
    private void writeCodeSection() throws IOException {
        int size = functions.size();
        if( size == 0 ) {
            return;
        }
        WasmOutputStream stream = new WasmOutputStream();
        stream.writeVaruint32( size );
        for( Function func : functions.values() ) {
            func.functionsStream.writeTo( stream );
        }
        wasm.writeSection( SectionType.Code, stream );
    }

    /**
     * Write optional the debug names into the name section
     * 
     * @throws IOException
     *             if any I/O error occur
     */
    private void writeDebugNames() throws IOException {
        if( !debugNames ) {
            return;
        }
        WasmOutputStream stream = new WasmOutputStream();
        stream.writeString( "name" ); // Custom Section name "name", content is part of the section length

        // write function names
        stream.write( 1 ); // 1 - Function name
        WasmOutputStream section = new WasmOutputStream();
        section.writeVaruint32( functions.size() );
        for( Entry<String, Function> entry : functions.entrySet() ) {
            section.writeVaruint32( entry.getValue().id ); // function index
            String functionName = entry.getKey();
            functionName = functionName.substring( 0, functionName.indexOf( '(' ) );
            section.writeString( functionName );
        }
        stream.writeVaruint32( section.size() );
        section.writeTo( stream );

        // write function parameter names
        stream.write( 2 ); // 2 - Local names
        section.reset();
        section.writeVaruint32( functions.size() );
        for( Entry<String, Function> entry : functions.entrySet() ) {
            Function func = entry.getValue();
            section.writeVaruint32( func.id ); // function index
            List<String> paramNames = func.paramNames;
            int count = paramNames == null ? 0 : paramNames.size();
            section.writeVaruint32( count ); // count of locals
            for( int i = 0; i < count; i++ ) {
                section.writeVaruint32( i );
                section.writeString( paramNames.get( i ) );
            }
        }
        stream.writeVaruint32( section.size() );
        section.writeTo( stream );

        wasm.writeSection( SectionType.Custom, stream );
    }

    /**
     * Write producer information to wasm
     * 
     * @throws IOException
     *             if any I/O error occur
     */
    private void writeProducersSection() throws IOException {
        Package pack = getClass().getPackage();
        String version = pack == null ? null : pack.getImplementationVersion();

        WasmOutputStream stream = new WasmOutputStream();
        stream.writeString( "producers" ); // Custom Section name "producers", content is part of the section length

        stream.writeVaruint32( 2 ); // field_count; number of fields that follow (language and processed-by)

        // field source language list
        stream.writeString( "language" );
        stream.writeVaruint32( 1 ); // field_value_count; number of value strings that follow
        stream.writeString( "Java bytecode" );

        // field individual tool list
        stream.writeString( "processed-by" );
        if( version == null ) {
            stream.writeVaruint32( 1 ); // field_value_count; number of value strings that follow
            stream.writeString( "JWebAssembly" );
        } else {
            stream.writeVaruint32( 2 ); // field_value_count; number of value strings that follow
            stream.writeString( "JWebAssembly" );
            stream.writeString( version );
        }

        wasm.writeSection( SectionType.Custom, stream );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int writeStruct( String typeName, List<NamedStorageType> fields ) throws IOException {
        int typeId = functionTypes.size();
        functionTypes.add( new StructTypeEntry( fields ) );
        return typeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeException() throws IOException {
        if( exceptionSignatureIndex <= 0 ) {
            FunctionTypeEntry exceptionType = new FunctionTypeEntry();
            exceptionType.params.add( ValueType.anyref );
            exceptionSignatureIndex = functionTypes.indexOf( exceptionType );
            if( exceptionSignatureIndex < 0 ) {
                exceptionSignatureIndex = functionTypes.size();
                functionTypes.add( exceptionType );
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void prepareImport( FunctionName name, String importModule, String importName ) {
        ImportFunction importFunction;
        function = importFunction = new ImportFunction(importModule, importName);
        imports.put( name.signatureName, importFunction );
        functionType = new FunctionTypeEntry();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepareFinish() {
        // initialize the function index IDs
        // https://github.com/WebAssembly/design/blob/master/Modules.md#function-index-space
        int id = 0;
        for( ImportFunction entry : imports.values() ) {
            entry.id = id++;
        }
        for( Function function : functions.values() ) {
            function.id = id++;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeExport( FunctionName name, String exportName ) throws IOException {
        exports.put( exportName, name.signatureName );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeMethodStart( FunctionName name ) throws IOException {
        function = getFunction( name );
        functionType = new FunctionTypeEntry();
        codeStream.reset();
        locals.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeMethodParam( String kind, AnyType valueType, @Nullable String name ) throws IOException {
        switch( kind ) {
            case "param":
                functionType.params.add( valueType );
                break;
            case "result":
                functionType.results.add( valueType );
                return;
            case "local":
                locals.add( valueType );
                break;
        }
        if( debugNames && name != null ) {
            if( function.paramNames == null ) {
                function.paramNames = new ArrayList<>();
            }
            function.paramNames.add( name );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeMethodParamFinish() throws IOException {
        int typeId = functionTypes.indexOf( functionType );
        if( typeId < 0 ) {
            typeId = functionTypes.size();
            functionTypes.add( functionType );
        }
        function.typeId = typeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void markCodePosition( int javaCodePosition ) {
        function.markCodePosition( codeStream.size(), javaCodePosition );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeMethodFinish() throws IOException {
        WasmOutputStream localsStream = new WasmOutputStream();
        localsStream.writeVaruint32( locals.size() );
        for( AnyType valueType : locals ) {
            localsStream.writeVaruint32( 1 ); // TODO optimize, write the count of same types.
            localsStream.writeValueType( valueType );
        }
        WasmOutputStream functionsStream = function.functionsStream = new WasmOutputStream();
        functionsStream.writeVaruint32( localsStream.size() + codeStream.size() + 1 );
        localsStream.writeTo( functionsStream );
        codeStream.writeTo( functionsStream );
        functionsStream.write( END );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeConst( Number value, ValueType valueType ) throws IOException {
        codeStream.writeConst( value, valueType );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeLocal( VariableOperator op, int idx ) throws IOException {
        int code;
        switch( op ) {
            case get:
                code = LOCAL_GET;
                break;
            case set:
                code = LOCAL_SET;
                break;
            default:
                code = LOCAL_TEE;
        }
        codeStream.writeOpCode( code );
        codeStream.writeVaruint32( idx );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeGlobalAccess( boolean load, FunctionName name, Member ref ) throws IOException {
        Global var = globals.get( name.fullName );
        if( var == null ) { // if not declared then create a definition in the global section
            var = new Global();
            var.id = globals.size();
            var.type = ValueType.getValueType( ref.getType() );
            var.mutability = true;
            globals.put( name.fullName, var );
        }
        int op = load ? GET_GLOBAL : SET_GLOBAL;
        codeStream.writeOpCode( op );
        codeStream.writeVaruint32( var.id );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeNumericOperator( NumericOperator numOp, @Nullable ValueType valueType ) throws IOException {
        int op = 0;
        switch( numOp ) {
            case add:
                switch( valueType ) {
                    case i32:
                        op = I32_ADD;
                        break;
                    case i64:
                        op = I64_ADD;
                        break;
                    case f32:
                        op = F32_ADD;
                        break;
                    case f64:
                        op = F64_ADD;
                        break;
                }
                break;
            case sub:
                switch( valueType ) {
                    case i32:
                        op = I32_SUB;
                        break;
                    case i64:
                        op = I64_SUB;
                        break;
                    case f32:
                        op = F32_SUB;
                        break;
                    case f64:
                        op = F64_SUB;
                        break;
                }
                break;
            case neg:
                switch( valueType ) {
                    case f32:
                        op = F32_NEG;
                        break;
                    case f64:
                        op = F64_NEG;
                        break;
                }
                break;
            case mul:
                switch( valueType ) {
                    case i32:
                        op = I32_MUL;
                        break;
                    case i64:
                        op = I64_MUL;
                        break;
                    case f32:
                        op = F32_MUL;
                        break;
                    case f64:
                        op = F64_MUL;
                        break;
                }
                break;
            case div:
                switch( valueType ) {
                    case i32:
                        op = I32_DIV_S;
                        break;
                    case i64:
                        op = I64_DIV_S;
                        break;
                    case f32:
                        op = F32_DIV;
                        break;
                    case f64:
                        op = F64_DIV;
                        break;
                }
                break;
            case rem:
                switch( valueType ) {
                    case i32:
                        op = I32_REM_S;
                        break;
                    case i64:
                        op = I64_REM_S;
                        break;
                }
                break;
            case and:
                switch( valueType ) {
                    case i32:
                        op = I32_AND;
                        break;
                    case i64:
                        op = I64_AND;
                        break;
                }
                break;
            case or:
                switch( valueType ) {
                    case i32:
                        op = I32_OR;
                        break;
                    case i64:
                        op = I64_OR;
                        break;
                }
                break;
            case xor:
                switch( valueType ) {
                    case i32:
                        op = I32_XOR;
                        break;
                    case i64:
                        op = I64_XOR;
                        break;
                }
                break;
            case shl:
                switch( valueType ) {
                    case i32:
                        op = I32_SHL;
                        break;
                    case i64:
                        op = I64_SHL;
                        break;
                }
                break;
            case shr_s:
                switch( valueType ) {
                    case i32:
                        op = I32_SHR_S;
                        break;
                    case i64:
                        op = I64_SHR_S;
                        break;
                }
                break;
            case shr_u:
                switch( valueType ) {
                    case i32:
                        op = I32_SHR_U;
                        break;
                    case i64:
                        op = I64_SHR_U;
                        break;
                }
                break;
            case eq:
                switch( valueType ) {
                    case i32:
                        op = I32_EQ;
                        break;
                    case i64:
                        op = I64_EQ;
                        break;
                    case f32:
                        op = F32_EQ;
                        break;
                    case f64:
                        op = F64_EQ;
                        break;
                }
                break;
            case ne:
                switch( valueType ) {
                    case i32:
                        op = I32_NE;
                        break;
                    case i64:
                        op = I64_NE;
                        break;
                    case f32:
                        op = F32_NE;
                        break;
                    case f64:
                        op = F64_NE;
                        break;
                }
                break;
            case gt:
                switch( valueType ) {
                    case i32:
                        op = I32_GT_S;
                        break;
                    case i64:
                        op = I64_GT_S;
                        break;
                    case f32:
                        op = F32_GT;
                        break;
                    case f64:
                        op = F64_GT;
                        break;
                }
                break;
            case lt:
                switch( valueType ) {
                    case i32:
                        op = I32_LT_S;
                        break;
                    case i64:
                        op = I64_LT_S;
                        break;
                    case f32:
                        op = F32_LT;
                        break;
                    case f64:
                        op = F64_LT;
                        break;
                }
                break;
            case le:
                switch( valueType ) {
                    case i32:
                        op = I32_LE_S;
                        break;
                    case i64:
                        op = I64_LE_S;
                        break;
                    case f32:
                        op = F32_LE;
                        break;
                    case f64:
                        op = F64_LE;
                        break;
                }
                break;
            case ge:
                switch( valueType ) {
                    case i32:
                        op = I32_GE_S;
                        break;
                    case i64:
                        op = I64_GE_S;
                        break;
                    case f32:
                        op = F32_GE;
                        break;
                    case f64:
                        op = F64_GE;
                        break;
                }
                break;
            case max:
                switch( valueType ) {
                    case f32:
                        op = F32_MAX;
                        break;
                    case f64:
                        op = F64_MAX;
                        break;
                }
                break;
            case ifnull:
                op = REF_ISNULL;
                break;
            case ifnonnull:
                codeStream.writeOpCode( REF_ISNULL );
                op = I32_EQZ;
                break;
            case ref_eq:
                op = REF_EQ;
                break;
            case ref_ne:
                codeStream.writeOpCode( REF_EQ );
                op = I32_EQZ;
                break;
        }
        if( op == 0 ) {
            throw new Error( valueType + "." + numOp );
        }
        codeStream.writeOpCode( op );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeCast( ValueTypeConvertion cast ) throws IOException {
        int op;
        switch( cast ) {
            case i2l:
                op = I64_EXTEND_I32_S;
                break;
            case i2f:
                op = F32_CONVERT_I32_S;
                break;
            case i2d:
                op = F64_CONVERT_I32_S;
                break;
            case l2i:
                op = I32_WRAP_I64;
                break;
            case l2f:
                op = F32_CONVERT_I64_S;
                break;
            case l2d:
                op = F64_CONVERT_I64_S;
                break;
            case f2i:
                op = I32_TRUNC_SAT_F32_S;
                break;
            case f2l:
                op = I64_TRUNC_SAT_F32_S;
                break;
            case f2d:
                op = F64_PROMOTE_F32;
                break;
            case d2i:
                op = I32_TRUNC_SAT_F64_S;
                break;
            case d2l:
                op = I64_TRUNC_SAT_F64_S;
                break;
            case d2f:
                op = F32_DEMOTE_F64;
                break;
            case i2b:
                op = I32_EXTEND8_S;
                break;
            case i2s:
                op = I32_EXTEND16_S;
                break;
            default:
                throw new Error( "Unknown cast: " + cast );
        }
        codeStream.writeOpCode( op );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeFunctionCall( FunctionName name ) throws IOException {
        Function func = getFunction( name );
        codeStream.writeOpCode( CALL );
        codeStream.writeVaruint32( func.id );
    }

    /**
     * Get the function object for the name. If not exists then it will be created.
     * 
     * @param name
     *            the function name
     * @return the function object
     */
    @Nonnull
    private Function getFunction( FunctionName name ) {
        String signatureName = name.signatureName;
        Function func = functions.get( signatureName );
        if( func == null ) {
            func = imports.get( signatureName );
            if( func == null ) {
                func = new Function();
                func.id = functions.size() + imports.size();
                functions.put( signatureName, func );
            }
        }
        return func;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeBlockCode( @Nonnull WasmBlockOperator op, @Nullable Object data ) throws IOException {
        switch( op ) {
            case RETURN:
                codeStream.writeOpCode( RETURN );
                break;
            case IF:
                codeStream.writeOpCode( IF );
                codeStream.writeValueType( ((ValueType)data) );
                break;
            case ELSE:
                codeStream.writeOpCode( ELSE );
                break;
            case END:
                codeStream.writeOpCode( END );
                break;
            case DROP:
                codeStream.writeOpCode( DROP );
                break;
            case BLOCK:
                codeStream.writeOpCode( BLOCK );
                codeStream.writeValueType( data == null ? ValueType.empty : (ValueType)data ); // void; the return type of the block. 
                break;
            case BR:
                codeStream.writeOpCode( BR );
                codeStream.writeVaruint32( (Integer)data );
                break;
            case BR_IF:
                codeStream.writeOpCode( BR_IF );
                codeStream.writeVaruint32( (Integer)data );
                break;
            case BR_TABLE:
                codeStream.writeOpCode( BR_TABLE );
                int[] targets = (int[])data;
                codeStream.writeVaruint32( targets.length - 1 );
                for( int i : targets ) {
                    codeStream.writeVaruint32( i );
                }
                break;
            case LOOP:
                codeStream.writeOpCode( LOOP );
                codeStream.writeValueType( ValueType.empty ); // void; the return type of the loop. currently we does not use it
                break;
            case UNREACHABLE:
                codeStream.writeOpCode( UNREACHABLE );
                break;
            case TRY:
                codeStream.writeOpCode( TRY );
                codeStream.writeValueType( ValueType.empty ); // void; the return type of the try. currently we does not use it
                break;
            case CATCH:
                codeStream.writeOpCode( CATCH );
                break;
            case THROW:
                codeStream.writeOpCode( THROW );
                codeStream.writeVaruint32( 0 );             // event/exception ever 0 because currently there is only one with signature anyref
                break;
            case RETHROW:
                codeStream.writeOpCode( RETHROW );
                break;
            case BR_ON_EXN:
                codeStream.writeOpCode( BR_ON_EXN );
                codeStream.writeVaruint32( (Integer)data ); // break depth
                codeStream.writeVaruint32( 0 );             // event/exception ever 0 because currently there is only one with signature anyref
                break;
            case MONITOR_ENTER:
            case MONITOR_EXIT:
                codeStream.writeOpCode( DROP );
                break;
            default:
                throw new Error( "Unknown block: " + op );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeArrayOperator( @Nonnull ArrayOperator op, AnyType type ) throws IOException {
        int opCode;
        switch(op) {
            case NEW:
                opCode = ARRAY_NEW;
                break;
            case GET:
                opCode = ARRAY_GET;
                break;
            case SET:
                opCode = ARRAY_SET;
                break;
            case LENGTH:
                opCode = ARRAY_LEN;
                break;
            default:
                throw new Error( "Unknown operator: " + op );
        }
        codeStream.writeOpCode( opCode );
        codeStream.writeValueType( type );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeStructOperator( StructOperator op, AnyType type, String fieldName ) throws IOException {
        int opCode;
        switch(op) {
            case NEW:
            case NEW_DEFAULT:
                opCode = STRUCT_NEW;
                break;
            case GET:
                opCode = STRUCT_GET;
                break;
            case SET:
                opCode = STRUCT_SET;
                break;
            case NULL:
                opCode = REF_NULL;
                type = null;
                break;
            default:
                throw new Error( "Unknown operator: " + op );
        }
        codeStream.writeOpCode( opCode );
        if( type != null ) {
            codeStream.writeValueType( type );
        }
        if( fieldName != null ) {
            StructTypeEntry entry = (StructTypeEntry)functionTypes.get( type.getCode() );
            codeStream.writeVaruint32( entry.getFieldIdx( fieldName ) );
        }
    }
}
