package org.omg.CosNaming;


/**
* org/omg/CosNaming/Binding.java .
* Generated by the IDL-to-Java compiler (portable), version "3.2"
* from c:/re/workspace/8-2-build-windows-amd64-cygwin/jdk8u161/10277/corba/src/share/classes/org/omg/CosNaming/nameservice.idl
* Tuesday, December 19, 2017 5:53:40 PM PST
*/

public final class Binding implements org.omg.CORBA.portable.IDLEntity
{
  public NameComponent binding_name[] = null;

  // name
  public BindingType binding_type = null;

  public Binding ()
  {
  } // ctor

  public Binding (NameComponent[] _binding_name, BindingType _binding_type)
  {
    binding_name = _binding_name;
    binding_type = _binding_type;
  } // ctor

} // class Binding
