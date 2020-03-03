// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: Types.proto

#include "Types.pb.h"

#include <algorithm>

#include <google/protobuf/stubs/common.h>
#include <google/protobuf/stubs/port.h>
#include <google/protobuf/io/coded_stream.h>
#include <google/protobuf/wire_format_lite_inl.h>
#include <google/protobuf/descriptor.h>
#include <google/protobuf/generated_message_reflection.h>
#include <google/protobuf/reflection_ops.h>
#include <google/protobuf/wire_format.h>
// This is a temporary google only hack
#ifdef GOOGLE_PROTOBUF_ENFORCE_UNIQUENESS
#include "third_party/protobuf/version.h"
#endif
// @@protoc_insertion_point(includes)

namespace common {
class MajorTypeDefaultTypeInternal {
 public:
  ::google::protobuf::internal::ExplicitlyConstructed<MajorType>
      _instance;
} _MajorType_default_instance_;
}  // namespace common
namespace protobuf_Types_2eproto {
static void InitDefaultsMajorType() {
  GOOGLE_PROTOBUF_VERIFY_VERSION;

  {
    void* ptr = &::common::_MajorType_default_instance_;
    new (ptr) ::common::MajorType();
    ::google::protobuf::internal::OnShutdownDestroyMessage(ptr);
  }
  ::common::MajorType::InitAsDefaultInstance();
}

::google::protobuf::internal::SCCInfo<0> scc_info_MajorType =
    {{ATOMIC_VAR_INIT(::google::protobuf::internal::SCCInfoBase::kUninitialized), 0, InitDefaultsMajorType}, {}};

void InitDefaults() {
  ::google::protobuf::internal::InitSCC(&scc_info_MajorType.base);
}

::google::protobuf::Metadata file_level_metadata[1];
const ::google::protobuf::EnumDescriptor* file_level_enum_descriptors[2];

const ::google::protobuf::uint32 TableStruct::offsets[] GOOGLE_PROTOBUF_ATTRIBUTE_SECTION_VARIABLE(protodesc_cold) = {
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, _has_bits_),
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, _internal_metadata_),
  ~0u,  // no _extensions_
  ~0u,  // no _oneof_case_
  ~0u,  // no _weak_field_map_
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, minor_type_),
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, mode_),
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, width_),
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, precision_),
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, scale_),
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, timezone_),
  GOOGLE_PROTOBUF_GENERATED_MESSAGE_FIELD_OFFSET(::common::MajorType, sub_type_),
  0,
  1,
  2,
  3,
  4,
  5,
  ~0u,
};
static const ::google::protobuf::internal::MigrationSchema schemas[] GOOGLE_PROTOBUF_ATTRIBUTE_SECTION_VARIABLE(protodesc_cold) = {
  { 0, 12, sizeof(::common::MajorType)},
};

static ::google::protobuf::Message const * const file_default_instances[] = {
  reinterpret_cast<const ::google::protobuf::Message*>(&::common::_MajorType_default_instance_),
};

void protobuf_AssignDescriptors() {
  AddDescriptors();
  AssignDescriptors(
      "Types.proto", schemas, file_default_instances, TableStruct::offsets,
      file_level_metadata, file_level_enum_descriptors, NULL);
}

void protobuf_AssignDescriptorsOnce() {
  static ::google::protobuf::internal::once_flag once;
  ::google::protobuf::internal::call_once(once, protobuf_AssignDescriptors);
}

void protobuf_RegisterTypes(const ::std::string&) GOOGLE_PROTOBUF_ATTRIBUTE_COLD;
void protobuf_RegisterTypes(const ::std::string&) {
  protobuf_AssignDescriptorsOnce();
  ::google::protobuf::internal::RegisterAllTypes(file_level_metadata, 1);
}

void AddDescriptorsImpl() {
  InitDefaults();
  static const char descriptor[] GOOGLE_PROTOBUF_ATTRIBUTE_SECTION_VARIABLE(protodesc_cold) = {
      "\n\013Types.proto\022\006common\"\272\001\n\tMajorType\022%\n\nm"
      "inor_type\030\001 \001(\0162\021.common.MinorType\022\036\n\004mo"
      "de\030\002 \001(\0162\020.common.DataMode\022\r\n\005width\030\003 \001("
      "\005\022\021\n\tprecision\030\004 \001(\005\022\r\n\005scale\030\005 \001(\005\022\020\n\010t"
      "imeZone\030\006 \001(\005\022#\n\010sub_type\030\007 \003(\0162\021.common"
      ".MinorType*\265\004\n\tMinorType\022\010\n\004LATE\020\000\022\007\n\003MA"
      "P\020\001\022\013\n\007TINYINT\020\003\022\014\n\010SMALLINT\020\004\022\007\n\003INT\020\005\022"
      "\n\n\006BIGINT\020\006\022\014\n\010DECIMAL9\020\007\022\r\n\tDECIMAL18\020\010"
      "\022\023\n\017DECIMAL28SPARSE\020\t\022\023\n\017DECIMAL38SPARSE"
      "\020\n\022\t\n\005MONEY\020\013\022\010\n\004DATE\020\014\022\010\n\004TIME\020\r\022\n\n\006TIM"
      "ETZ\020\016\022\017\n\013TIMESTAMPTZ\020\017\022\r\n\tTIMESTAMP\020\020\022\014\n"
      "\010INTERVAL\020\021\022\n\n\006FLOAT4\020\022\022\n\n\006FLOAT8\020\023\022\007\n\003B"
      "IT\020\024\022\r\n\tFIXEDCHAR\020\025\022\017\n\013FIXED16CHAR\020\026\022\017\n\013"
      "FIXEDBINARY\020\027\022\013\n\007VARCHAR\020\030\022\r\n\tVAR16CHAR\020"
      "\031\022\r\n\tVARBINARY\020\032\022\t\n\005UINT1\020\035\022\t\n\005UINT2\020\036\022\t"
      "\n\005UINT4\020\037\022\t\n\005UINT8\020 \022\022\n\016DECIMAL28DENSE\020!"
      "\022\022\n\016DECIMAL38DENSE\020\"\022\016\n\nDM_UNKNOWN\020%\022\020\n\014"
      "INTERVALYEAR\020&\022\017\n\013INTERVALDAY\020\'\022\010\n\004LIST\020"
      "(\022\022\n\016GENERIC_OBJECT\020)\022\t\n\005UNION\020*\022\016\n\nVARD"
      "ECIMAL\020+\022\010\n\004DICT\020,*=\n\010DataMode\022\017\n\013DM_OPT"
      "IONAL\020\000\022\017\n\013DM_REQUIRED\020\001\022\017\n\013DM_REPEATED\020"
      "\002B-\n\035org.apache.drill.common.typesB\nType"
      "ProtosH\001"
  };
  ::google::protobuf::DescriptorPool::InternalAddGeneratedFile(
      descriptor, 888);
  ::google::protobuf::MessageFactory::InternalRegisterGeneratedFile(
    "Types.proto", &protobuf_RegisterTypes);
}

void AddDescriptors() {
  static ::google::protobuf::internal::once_flag once;
  ::google::protobuf::internal::call_once(once, AddDescriptorsImpl);
}
// Force AddDescriptors() to be called at dynamic initialization time.
struct StaticDescriptorInitializer {
  StaticDescriptorInitializer() {
    AddDescriptors();
  }
} static_descriptor_initializer;
}  // namespace protobuf_Types_2eproto
namespace common {
const ::google::protobuf::EnumDescriptor* MinorType_descriptor() {
  protobuf_Types_2eproto::protobuf_AssignDescriptorsOnce();
  return protobuf_Types_2eproto::file_level_enum_descriptors[0];
}
bool MinorType_IsValid(int value) {
  switch (value) {
    case 0:
    case 1:
    case 3:
    case 4:
    case 5:
    case 6:
    case 7:
    case 8:
    case 9:
    case 10:
    case 11:
    case 12:
    case 13:
    case 14:
    case 15:
    case 16:
    case 17:
    case 18:
    case 19:
    case 20:
    case 21:
    case 22:
    case 23:
    case 24:
    case 25:
    case 26:
    case 29:
    case 30:
    case 31:
    case 32:
    case 33:
    case 34:
    case 37:
    case 38:
    case 39:
    case 40:
    case 41:
    case 42:
    case 43:
    case 44:
      return true;
    default:
      return false;
  }
}

const ::google::protobuf::EnumDescriptor* DataMode_descriptor() {
  protobuf_Types_2eproto::protobuf_AssignDescriptorsOnce();
  return protobuf_Types_2eproto::file_level_enum_descriptors[1];
}
bool DataMode_IsValid(int value) {
  switch (value) {
    case 0:
    case 1:
    case 2:
      return true;
    default:
      return false;
  }
}


// ===================================================================

void MajorType::InitAsDefaultInstance() {
}
#if !defined(_MSC_VER) || _MSC_VER >= 1900
const int MajorType::kMinorTypeFieldNumber;
const int MajorType::kModeFieldNumber;
const int MajorType::kWidthFieldNumber;
const int MajorType::kPrecisionFieldNumber;
const int MajorType::kScaleFieldNumber;
const int MajorType::kTimeZoneFieldNumber;
const int MajorType::kSubTypeFieldNumber;
#endif  // !defined(_MSC_VER) || _MSC_VER >= 1900

MajorType::MajorType()
  : ::google::protobuf::Message(), _internal_metadata_(NULL) {
  ::google::protobuf::internal::InitSCC(
      &protobuf_Types_2eproto::scc_info_MajorType.base);
  SharedCtor();
  // @@protoc_insertion_point(constructor:common.MajorType)
}
MajorType::MajorType(const MajorType& from)
  : ::google::protobuf::Message(),
      _internal_metadata_(NULL),
      _has_bits_(from._has_bits_),
      sub_type_(from.sub_type_) {
  _internal_metadata_.MergeFrom(from._internal_metadata_);
  ::memcpy(&minor_type_, &from.minor_type_,
    static_cast<size_t>(reinterpret_cast<char*>(&timezone_) -
    reinterpret_cast<char*>(&minor_type_)) + sizeof(timezone_));
  // @@protoc_insertion_point(copy_constructor:common.MajorType)
}

void MajorType::SharedCtor() {
  ::memset(&minor_type_, 0, static_cast<size_t>(
      reinterpret_cast<char*>(&timezone_) -
      reinterpret_cast<char*>(&minor_type_)) + sizeof(timezone_));
}

MajorType::~MajorType() {
  // @@protoc_insertion_point(destructor:common.MajorType)
  SharedDtor();
}

void MajorType::SharedDtor() {
}

void MajorType::SetCachedSize(int size) const {
  _cached_size_.Set(size);
}
const ::google::protobuf::Descriptor* MajorType::descriptor() {
  ::protobuf_Types_2eproto::protobuf_AssignDescriptorsOnce();
  return ::protobuf_Types_2eproto::file_level_metadata[kIndexInFileMessages].descriptor;
}

const MajorType& MajorType::default_instance() {
  ::google::protobuf::internal::InitSCC(&protobuf_Types_2eproto::scc_info_MajorType.base);
  return *internal_default_instance();
}


void MajorType::Clear() {
// @@protoc_insertion_point(message_clear_start:common.MajorType)
  ::google::protobuf::uint32 cached_has_bits = 0;
  // Prevent compiler warnings about cached_has_bits being unused
  (void) cached_has_bits;

  sub_type_.Clear();
  cached_has_bits = _has_bits_[0];
  if (cached_has_bits & 63u) {
    ::memset(&minor_type_, 0, static_cast<size_t>(
        reinterpret_cast<char*>(&timezone_) -
        reinterpret_cast<char*>(&minor_type_)) + sizeof(timezone_));
  }
  _has_bits_.Clear();
  _internal_metadata_.Clear();
}

bool MajorType::MergePartialFromCodedStream(
    ::google::protobuf::io::CodedInputStream* input) {
#define DO_(EXPRESSION) if (!GOOGLE_PREDICT_TRUE(EXPRESSION)) goto failure
  ::google::protobuf::uint32 tag;
  // @@protoc_insertion_point(parse_start:common.MajorType)
  for (;;) {
    ::std::pair<::google::protobuf::uint32, bool> p = input->ReadTagWithCutoffNoLastTag(127u);
    tag = p.first;
    if (!p.second) goto handle_unusual;
    switch (::google::protobuf::internal::WireFormatLite::GetTagFieldNumber(tag)) {
      // optional .common.MinorType minor_type = 1;
      case 1: {
        if (static_cast< ::google::protobuf::uint8>(tag) ==
            static_cast< ::google::protobuf::uint8>(8u /* 8 & 0xFF */)) {
          int value;
          DO_((::google::protobuf::internal::WireFormatLite::ReadPrimitive<
                   int, ::google::protobuf::internal::WireFormatLite::TYPE_ENUM>(
                 input, &value)));
          if (::common::MinorType_IsValid(value)) {
            set_minor_type(static_cast< ::common::MinorType >(value));
          } else {
            mutable_unknown_fields()->AddVarint(
                1, static_cast< ::google::protobuf::uint64>(value));
          }
        } else {
          goto handle_unusual;
        }
        break;
      }

      // optional .common.DataMode mode = 2;
      case 2: {
        if (static_cast< ::google::protobuf::uint8>(tag) ==
            static_cast< ::google::protobuf::uint8>(16u /* 16 & 0xFF */)) {
          int value;
          DO_((::google::protobuf::internal::WireFormatLite::ReadPrimitive<
                   int, ::google::protobuf::internal::WireFormatLite::TYPE_ENUM>(
                 input, &value)));
          if (::common::DataMode_IsValid(value)) {
            set_mode(static_cast< ::common::DataMode >(value));
          } else {
            mutable_unknown_fields()->AddVarint(
                2, static_cast< ::google::protobuf::uint64>(value));
          }
        } else {
          goto handle_unusual;
        }
        break;
      }

      // optional int32 width = 3;
      case 3: {
        if (static_cast< ::google::protobuf::uint8>(tag) ==
            static_cast< ::google::protobuf::uint8>(24u /* 24 & 0xFF */)) {
          set_has_width();
          DO_((::google::protobuf::internal::WireFormatLite::ReadPrimitive<
                   ::google::protobuf::int32, ::google::protobuf::internal::WireFormatLite::TYPE_INT32>(
                 input, &width_)));
        } else {
          goto handle_unusual;
        }
        break;
      }

      // optional int32 precision = 4;
      case 4: {
        if (static_cast< ::google::protobuf::uint8>(tag) ==
            static_cast< ::google::protobuf::uint8>(32u /* 32 & 0xFF */)) {
          set_has_precision();
          DO_((::google::protobuf::internal::WireFormatLite::ReadPrimitive<
                   ::google::protobuf::int32, ::google::protobuf::internal::WireFormatLite::TYPE_INT32>(
                 input, &precision_)));
        } else {
          goto handle_unusual;
        }
        break;
      }

      // optional int32 scale = 5;
      case 5: {
        if (static_cast< ::google::protobuf::uint8>(tag) ==
            static_cast< ::google::protobuf::uint8>(40u /* 40 & 0xFF */)) {
          set_has_scale();
          DO_((::google::protobuf::internal::WireFormatLite::ReadPrimitive<
                   ::google::protobuf::int32, ::google::protobuf::internal::WireFormatLite::TYPE_INT32>(
                 input, &scale_)));
        } else {
          goto handle_unusual;
        }
        break;
      }

      // optional int32 timeZone = 6;
      case 6: {
        if (static_cast< ::google::protobuf::uint8>(tag) ==
            static_cast< ::google::protobuf::uint8>(48u /* 48 & 0xFF */)) {
          set_has_timezone();
          DO_((::google::protobuf::internal::WireFormatLite::ReadPrimitive<
                   ::google::protobuf::int32, ::google::protobuf::internal::WireFormatLite::TYPE_INT32>(
                 input, &timezone_)));
        } else {
          goto handle_unusual;
        }
        break;
      }

      // repeated .common.MinorType sub_type = 7;
      case 7: {
        if (static_cast< ::google::protobuf::uint8>(tag) ==
            static_cast< ::google::protobuf::uint8>(56u /* 56 & 0xFF */)) {
          int value;
          DO_((::google::protobuf::internal::WireFormatLite::ReadPrimitive<
                   int, ::google::protobuf::internal::WireFormatLite::TYPE_ENUM>(
                 input, &value)));
          if (::common::MinorType_IsValid(value)) {
            add_sub_type(static_cast< ::common::MinorType >(value));
          } else {
            mutable_unknown_fields()->AddVarint(
                7, static_cast< ::google::protobuf::uint64>(value));
          }
        } else if (
            static_cast< ::google::protobuf::uint8>(tag) ==
            static_cast< ::google::protobuf::uint8>(58u /* 58 & 0xFF */)) {
          DO_((::google::protobuf::internal::WireFormat::ReadPackedEnumPreserveUnknowns(
                 input,
                 7,
                 ::common::MinorType_IsValid,
                 mutable_unknown_fields(),
                 this->mutable_sub_type())));
        } else {
          goto handle_unusual;
        }
        break;
      }

      default: {
      handle_unusual:
        if (tag == 0) {
          goto success;
        }
        DO_(::google::protobuf::internal::WireFormat::SkipField(
              input, tag, _internal_metadata_.mutable_unknown_fields()));
        break;
      }
    }
  }
success:
  // @@protoc_insertion_point(parse_success:common.MajorType)
  return true;
failure:
  // @@protoc_insertion_point(parse_failure:common.MajorType)
  return false;
#undef DO_
}

void MajorType::SerializeWithCachedSizes(
    ::google::protobuf::io::CodedOutputStream* output) const {
  // @@protoc_insertion_point(serialize_start:common.MajorType)
  ::google::protobuf::uint32 cached_has_bits = 0;
  (void) cached_has_bits;

  cached_has_bits = _has_bits_[0];
  // optional .common.MinorType minor_type = 1;
  if (cached_has_bits & 0x00000001u) {
    ::google::protobuf::internal::WireFormatLite::WriteEnum(
      1, this->minor_type(), output);
  }

  // optional .common.DataMode mode = 2;
  if (cached_has_bits & 0x00000002u) {
    ::google::protobuf::internal::WireFormatLite::WriteEnum(
      2, this->mode(), output);
  }

  // optional int32 width = 3;
  if (cached_has_bits & 0x00000004u) {
    ::google::protobuf::internal::WireFormatLite::WriteInt32(3, this->width(), output);
  }

  // optional int32 precision = 4;
  if (cached_has_bits & 0x00000008u) {
    ::google::protobuf::internal::WireFormatLite::WriteInt32(4, this->precision(), output);
  }

  // optional int32 scale = 5;
  if (cached_has_bits & 0x00000010u) {
    ::google::protobuf::internal::WireFormatLite::WriteInt32(5, this->scale(), output);
  }

  // optional int32 timeZone = 6;
  if (cached_has_bits & 0x00000020u) {
    ::google::protobuf::internal::WireFormatLite::WriteInt32(6, this->timezone(), output);
  }

  // repeated .common.MinorType sub_type = 7;
  for (int i = 0, n = this->sub_type_size(); i < n; i++) {
    ::google::protobuf::internal::WireFormatLite::WriteEnum(
      7, this->sub_type(i), output);
  }

  if (_internal_metadata_.have_unknown_fields()) {
    ::google::protobuf::internal::WireFormat::SerializeUnknownFields(
        _internal_metadata_.unknown_fields(), output);
  }
  // @@protoc_insertion_point(serialize_end:common.MajorType)
}

::google::protobuf::uint8* MajorType::InternalSerializeWithCachedSizesToArray(
    bool deterministic, ::google::protobuf::uint8* target) const {
  (void)deterministic; // Unused
  // @@protoc_insertion_point(serialize_to_array_start:common.MajorType)
  ::google::protobuf::uint32 cached_has_bits = 0;
  (void) cached_has_bits;

  cached_has_bits = _has_bits_[0];
  // optional .common.MinorType minor_type = 1;
  if (cached_has_bits & 0x00000001u) {
    target = ::google::protobuf::internal::WireFormatLite::WriteEnumToArray(
      1, this->minor_type(), target);
  }

  // optional .common.DataMode mode = 2;
  if (cached_has_bits & 0x00000002u) {
    target = ::google::protobuf::internal::WireFormatLite::WriteEnumToArray(
      2, this->mode(), target);
  }

  // optional int32 width = 3;
  if (cached_has_bits & 0x00000004u) {
    target = ::google::protobuf::internal::WireFormatLite::WriteInt32ToArray(3, this->width(), target);
  }

  // optional int32 precision = 4;
  if (cached_has_bits & 0x00000008u) {
    target = ::google::protobuf::internal::WireFormatLite::WriteInt32ToArray(4, this->precision(), target);
  }

  // optional int32 scale = 5;
  if (cached_has_bits & 0x00000010u) {
    target = ::google::protobuf::internal::WireFormatLite::WriteInt32ToArray(5, this->scale(), target);
  }

  // optional int32 timeZone = 6;
  if (cached_has_bits & 0x00000020u) {
    target = ::google::protobuf::internal::WireFormatLite::WriteInt32ToArray(6, this->timezone(), target);
  }

  // repeated .common.MinorType sub_type = 7;
  target = ::google::protobuf::internal::WireFormatLite::WriteEnumToArray(
    7, this->sub_type_, target);

  if (_internal_metadata_.have_unknown_fields()) {
    target = ::google::protobuf::internal::WireFormat::SerializeUnknownFieldsToArray(
        _internal_metadata_.unknown_fields(), target);
  }
  // @@protoc_insertion_point(serialize_to_array_end:common.MajorType)
  return target;
}

size_t MajorType::ByteSizeLong() const {
// @@protoc_insertion_point(message_byte_size_start:common.MajorType)
  size_t total_size = 0;

  if (_internal_metadata_.have_unknown_fields()) {
    total_size +=
      ::google::protobuf::internal::WireFormat::ComputeUnknownFieldsSize(
        _internal_metadata_.unknown_fields());
  }
  // repeated .common.MinorType sub_type = 7;
  {
    size_t data_size = 0;
    unsigned int count = static_cast<unsigned int>(this->sub_type_size());for (unsigned int i = 0; i < count; i++) {
      data_size += ::google::protobuf::internal::WireFormatLite::EnumSize(
        this->sub_type(static_cast<int>(i)));
    }
    total_size += (1UL * count) + data_size;
  }

  if (_has_bits_[0 / 32] & 63u) {
    // optional .common.MinorType minor_type = 1;
    if (has_minor_type()) {
      total_size += 1 +
        ::google::protobuf::internal::WireFormatLite::EnumSize(this->minor_type());
    }

    // optional .common.DataMode mode = 2;
    if (has_mode()) {
      total_size += 1 +
        ::google::protobuf::internal::WireFormatLite::EnumSize(this->mode());
    }

    // optional int32 width = 3;
    if (has_width()) {
      total_size += 1 +
        ::google::protobuf::internal::WireFormatLite::Int32Size(
          this->width());
    }

    // optional int32 precision = 4;
    if (has_precision()) {
      total_size += 1 +
        ::google::protobuf::internal::WireFormatLite::Int32Size(
          this->precision());
    }

    // optional int32 scale = 5;
    if (has_scale()) {
      total_size += 1 +
        ::google::protobuf::internal::WireFormatLite::Int32Size(
          this->scale());
    }

    // optional int32 timeZone = 6;
    if (has_timezone()) {
      total_size += 1 +
        ::google::protobuf::internal::WireFormatLite::Int32Size(
          this->timezone());
    }

  }
  int cached_size = ::google::protobuf::internal::ToCachedSize(total_size);
  SetCachedSize(cached_size);
  return total_size;
}

void MajorType::MergeFrom(const ::google::protobuf::Message& from) {
// @@protoc_insertion_point(generalized_merge_from_start:common.MajorType)
  GOOGLE_DCHECK_NE(&from, this);
  const MajorType* source =
      ::google::protobuf::internal::DynamicCastToGenerated<const MajorType>(
          &from);
  if (source == NULL) {
  // @@protoc_insertion_point(generalized_merge_from_cast_fail:common.MajorType)
    ::google::protobuf::internal::ReflectionOps::Merge(from, this);
  } else {
  // @@protoc_insertion_point(generalized_merge_from_cast_success:common.MajorType)
    MergeFrom(*source);
  }
}

void MajorType::MergeFrom(const MajorType& from) {
// @@protoc_insertion_point(class_specific_merge_from_start:common.MajorType)
  GOOGLE_DCHECK_NE(&from, this);
  _internal_metadata_.MergeFrom(from._internal_metadata_);
  ::google::protobuf::uint32 cached_has_bits = 0;
  (void) cached_has_bits;

  sub_type_.MergeFrom(from.sub_type_);
  cached_has_bits = from._has_bits_[0];
  if (cached_has_bits & 63u) {
    if (cached_has_bits & 0x00000001u) {
      minor_type_ = from.minor_type_;
    }
    if (cached_has_bits & 0x00000002u) {
      mode_ = from.mode_;
    }
    if (cached_has_bits & 0x00000004u) {
      width_ = from.width_;
    }
    if (cached_has_bits & 0x00000008u) {
      precision_ = from.precision_;
    }
    if (cached_has_bits & 0x00000010u) {
      scale_ = from.scale_;
    }
    if (cached_has_bits & 0x00000020u) {
      timezone_ = from.timezone_;
    }
    _has_bits_[0] |= cached_has_bits;
  }
}

void MajorType::CopyFrom(const ::google::protobuf::Message& from) {
// @@protoc_insertion_point(generalized_copy_from_start:common.MajorType)
  if (&from == this) return;
  Clear();
  MergeFrom(from);
}

void MajorType::CopyFrom(const MajorType& from) {
// @@protoc_insertion_point(class_specific_copy_from_start:common.MajorType)
  if (&from == this) return;
  Clear();
  MergeFrom(from);
}

bool MajorType::IsInitialized() const {
  return true;
}

void MajorType::Swap(MajorType* other) {
  if (other == this) return;
  InternalSwap(other);
}
void MajorType::InternalSwap(MajorType* other) {
  using std::swap;
  sub_type_.InternalSwap(&other->sub_type_);
  swap(minor_type_, other->minor_type_);
  swap(mode_, other->mode_);
  swap(width_, other->width_);
  swap(precision_, other->precision_);
  swap(scale_, other->scale_);
  swap(timezone_, other->timezone_);
  swap(_has_bits_[0], other->_has_bits_[0]);
  _internal_metadata_.Swap(&other->_internal_metadata_);
}

::google::protobuf::Metadata MajorType::GetMetadata() const {
  protobuf_Types_2eproto::protobuf_AssignDescriptorsOnce();
  return ::protobuf_Types_2eproto::file_level_metadata[kIndexInFileMessages];
}


// @@protoc_insertion_point(namespace_scope)
}  // namespace common
namespace google {
namespace protobuf {
template<> GOOGLE_PROTOBUF_ATTRIBUTE_NOINLINE ::common::MajorType* Arena::CreateMaybeMessage< ::common::MajorType >(Arena* arena) {
  return Arena::CreateInternal< ::common::MajorType >(arena);
}
}  // namespace protobuf
}  // namespace google

// @@protoc_insertion_point(global_scope)
