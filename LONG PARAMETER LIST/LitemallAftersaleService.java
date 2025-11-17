@Service public class LitemallAftersaleService {
  @Resource private LitemallAftersaleMapper aftersaleMapper;
  public LitemallAftersale findById(  Integer id){
    return aftersaleMapper.selectByPrimaryKey(id);
  }
  public LitemallAftersale findById(  Integer userId,  Integer id){
    LitemallAftersaleExample example=new LitemallAftersaleExample();
    example.or().andIdEqualTo(id).andUserIdEqualTo(userId).andDeletedEqualTo(false);
    return aftersaleMapper.selectOneByExample(example);
  }
  public List<LitemallAftersale> queryList(  Integer userId,  Short status,  Integer page,  Integer limit,  String sort,  String order){
    LitemallAftersaleExample example=new LitemallAftersaleExample();
    LitemallAftersaleExample.Criteria criteria=example.or();
    criteria.andUserIdEqualTo(userId);
    if (status != null) {
      criteria.andStatusEqualTo(status);
    }
    criteria.andDeletedEqualTo(false);
    if (!StringUtils.isEmpty(sort) && !StringUtils.isEmpty(order)) {
      example.setOrderByClause(sort + " " + order);
    }
 else {
      example.setOrderByClause(LitemallAftersale.Column.addTime.desc());
    }
    PageHelper.startPage(page,limit);
    return aftersaleMapper.selectByExample(example);
  }
  private String getRandomNum(  Integer num){
    String base="0123456789";
    Random random=new Random();
    StringBuffer sb=new StringBuffer();
    for (int i=0; i < num; i++) {
      int number=random.nextInt(base.length());
      sb.append(base.charAt(number));
    }
    return sb.toString();
  }
  public int countByAftersaleSn(  Integer userId,  String aftersaleSn){
    LitemallAftersaleExample example=new LitemallAftersaleExample();
    example.or().andUserIdEqualTo(userId).andAftersaleSnEqualTo(aftersaleSn).andDeletedEqualTo(false);
    return (int)aftersaleMapper.countByExample(example);
  }
  public String generateAftersaleSn(  Integer userId){
    DateTimeFormatter df=DateTimeFormatter.ofPattern("yyyyMMdd");
    String now=df.format(LocalDate.now());
    String aftersaleSn=now + getRandomNum(6);
    while (countByAftersaleSn(userId,aftersaleSn) != 0) {
      aftersaleSn=now + getRandomNum(6);
    }
    return aftersaleSn;
  }
  public void add(  LitemallAftersale aftersale){
    aftersale.setAddTime(LocalDateTime.now());
    aftersale.setUpdateTime(LocalDateTime.now());
    aftersaleMapper.insertSelective(aftersale);
  }
  public void deleteById(  Integer id){
    aftersaleMapper.logicalDeleteByPrimaryKey(id);
  }
  public void deleteByOrderId(  Integer userId,  Integer orderId){
    LitemallAftersaleExample example=new LitemallAftersaleExample();
    example.or().andOrderIdEqualTo(orderId).andUserIdEqualTo(userId).andDeletedEqualTo(false);
    LitemallAftersale aftersale=new LitemallAftersale();
    aftersale.setUpdateTime(LocalDateTime.now());
    aftersale.setDeleted(true);
    aftersaleMapper.updateByExampleSelective(aftersale,example);
  }
  public void updateById(  LitemallAftersale aftersale){
    aftersale.setUpdateTime(LocalDateTime.now());
    aftersaleMapper.updateByPrimaryKeySelective(aftersale);
  }
  public LitemallAftersale findByOrderId(  Integer userId,  Integer orderId){
    LitemallAftersaleExample example=new LitemallAftersaleExample();
    example.or().andOrderIdEqualTo(orderId).andUserIdEqualTo(userId).andDeletedEqualTo(false);
    return aftersaleMapper.selectOneByExample(example);
  }
}
