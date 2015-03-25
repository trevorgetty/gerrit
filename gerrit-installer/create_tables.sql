CREATE TABLE `slave_wait_id` (
  `s` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  UNIQUE KEY `s` (`s`)
);


CREATE TABLE `slave_waits` (
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `status` char(1) NOT NULL DEFAULT '',
  `wait_id` int(11) NOT NULL DEFAULT '0',
  PRIMARY KEY (`wait_id`)
);
